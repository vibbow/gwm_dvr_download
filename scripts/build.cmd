@echo off
rem 一键构建 release APK（双击运行）。参数会原样传给 build.ps1，例如：build.cmd -SkipTests -Clean
pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
set EXITCODE=%ERRORLEVEL%
rem 双击运行时暂停，方便查看结果
echo %CMDCMDLINE% | findstr /i /c:"/c" >nul && pause
exit /b %EXITCODE%
