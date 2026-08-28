@echo off
cd /d "%~dp0"
git add -A
git commit -m "Sopro v0.5.7 Windows golden replay"
git push
pause
