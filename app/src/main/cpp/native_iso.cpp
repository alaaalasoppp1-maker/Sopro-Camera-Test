#include <jni.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <errno.h>
#include <cstring>
#include <cstdlib>
#include <vector>
#include <chrono>
#include <thread>
#include <mutex>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SoproISO", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "SoproISO", __VA_ARGS__)

struct UrbSlot {
    usbdevfs_urb* urb = nullptr;
    unsigned char* buffer = nullptr;
    size_t urbSize = 0;
    size_t bufferSize = 0;
    bool submitted = false;
};

static std::mutex gMutex;
static std::vector<UrbSlot> gSlots;
static int gFd = -1;
static int gPacketSize = 0;
static int gPacketCount = 0;
static int gLastErrno = 0;

static void clear_desc(UrbSlot& slot) {
    slot.urb->status = 0;
    slot.urb->actual_length = 0;
    slot.urb->error_count = 0;
    for (int i=0;i<gPacketCount;i++) {
        slot.urb->iso_frame_desc[i].length = gPacketSize;
        slot.urb->iso_frame_desc[i].actual_length = 0;
        slot.urb->iso_frame_desc[i].status = 0;
    }
}

static bool submit_slot(UrbSlot& slot) {
    clear_desc(slot);
    if (ioctl(gFd, USBDEVFS_SUBMITURB, slot.urb) < 0) {
        gLastErrno = errno;
        LOGE("SUBMITURB failed errno=%d", errno);
        slot.submitted = false;
        return false;
    }
    slot.submitted = true;
    return true;
}

static UrbSlot* find_slot(usbdevfs_urb* urb) {
    for (auto& s: gSlots) if (s.urb == urb) return &s;
    return nullptr;
}

static void stop_locked() {
    if (gFd >= 0) {
        for (auto& s: gSlots) if (s.submitted && s.urb) ioctl(gFd, USBDEVFS_DISCARDURB, s.urb);
        auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
        while (std::chrono::steady_clock::now() < deadline) {
            usbdevfs_urb* done=nullptr;
            if (ioctl(gFd, USBDEVFS_REAPURBNDELAY, &done)==0 && done) {
                UrbSlot* s=find_slot(done); if(s) s->submitted=false; continue;
            }
            if (errno==EAGAIN || errno==EINTR) { std::this_thread::sleep_for(std::chrono::milliseconds(2)); continue; }
            break;
        }
    }
    for (auto& s: gSlots) { if(s.buffer) std::free(s.buffer); if(s.urb) std::free(s.urb); }
    gSlots.clear(); gFd=-1; gPacketSize=0; gPacketCount=0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dtdc_soprotest_MainActivity_nativeIsoStart(JNIEnv*, jobject, jint fd, jint endpoint, jint packetSize, jint packetCount, jint urbCount) {
    std::lock_guard<std::mutex> lock(gMutex);
    stop_locked();
    if(fd<0 || packetSize<=0 || packetCount<=0 || urbCount<2){ gLastErrno=EINVAL; return JNI_FALSE; }
    gFd=fd; gPacketSize=packetSize; gPacketCount=packetCount; gSlots.resize(urbCount);
    for(int u=0;u<urbCount;u++){
        UrbSlot& s=gSlots[u];
        s.urbSize=sizeof(usbdevfs_urb)+sizeof(usbdevfs_iso_packet_desc)*packetCount;
        s.bufferSize=(size_t)packetSize*packetCount;
        s.urb=(usbdevfs_urb*)std::calloc(1,s.urbSize);
        s.buffer=(unsigned char*)std::malloc(s.bufferSize);
        if(!s.urb || !s.buffer){ gLastErrno=ENOMEM; stop_locked(); return JNI_FALSE; }
        std::memset(s.buffer,0,s.bufferSize);
        s.urb->type=USBDEVFS_URB_TYPE_ISO;
        s.urb->endpoint=endpoint;
        s.urb->flags=USBDEVFS_URB_ISO_ASAP;
        s.urb->buffer=s.buffer;
        s.urb->buffer_length=(int)s.bufferSize;
        s.urb->number_of_packets=packetCount;
        if(!submit_slot(s)){ stop_locked(); return JNI_FALSE; }
    }
    LOGI("ISO queue started fd=%d ep=0x%02x pkt=%d packets=%d urbs=%d",fd,endpoint,packetSize,packetCount,urbCount);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_dtdc_soprotest_MainActivity_nativeIsoRead(JNIEnv* env, jobject, jint timeoutMs) {
    std::lock_guard<std::mutex> lock(gMutex);
    if(gFd<0 || gSlots.empty()) return nullptr;
    usbdevfs_urb* completed=nullptr;
    auto deadline=std::chrono::steady_clock::now()+std::chrono::milliseconds(timeoutMs);
    while(std::chrono::steady_clock::now()<deadline){
        if(ioctl(gFd,USBDEVFS_REAPURBNDELAY,&completed)==0 && completed) break;
        gLastErrno=errno;
        if(errno!=EAGAIN && errno!=EINTR){ LOGE("REAPURBNDELAY errno=%d",errno); return nullptr; }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    if(!completed) return nullptr;
    UrbSlot* s=find_slot(completed); if(!s){ gLastErrno=EFAULT; return nullptr; }
    s->submitted=false;
    std::vector<unsigned char> out; out.reserve(s->bufferSize+gPacketCount*2);
    size_t packetOffset=0;
    for(int i=0;i<gPacketCount;i++){
        auto& d=completed->iso_frame_desc[i]; int n=d.actual_length;
        if(d.status==0 && n>0 && packetOffset+(size_t)n<=s->bufferSize){
            out.push_back((unsigned char)(n&0xff)); out.push_back((unsigned char)((n>>8)&0xff));
            unsigned char* p=s->buffer+packetOffset; out.insert(out.end(),p,p+n);
        }
        packetOffset += d.length;
    }
    if(!submit_slot(*s)) return nullptr;
    if(out.empty()) return nullptr;
    jbyteArray arr=env->NewByteArray((jsize)out.size());
    if(arr) env->SetByteArrayRegion(arr,0,(jsize)out.size(),reinterpret_cast<const jbyte*>(out.data()));
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dtdc_soprotest_MainActivity_nativeIsoStop(JNIEnv*, jobject){ std::lock_guard<std::mutex> lock(gMutex); stop_locked(); }
extern "C" JNIEXPORT jint JNICALL
Java_com_dtdc_soprotest_MainActivity_nativeLastErrno(JNIEnv*, jobject){ return gLastErrno; }
