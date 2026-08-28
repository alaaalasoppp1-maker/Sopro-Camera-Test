@echo off
setlocal
echo.
echo DTDC - Update current GitHub repository
echo =======================================
echo Copy/extract this package INTO your existing local repository folder.
echo Then run this file from that repository.
echo.
git add -A
git commit -m "Update Sopro Camera Player v0.5.5"
if errorlevel 1 (
  echo.
  echo Nothing new to commit, or Git needs attention.
)
git push
echo.
echo Done. Check GitHub Actions.
pause
