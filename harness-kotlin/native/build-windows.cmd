@echo off
setlocal
call "%ProgramFiles(x86)%\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\VsDevCmd.bat" -arch=x64 >nul
if errorlevel 1 exit /b 1
cl /nologo /W4 /WX /MT /I"%~1\include" /I"%~1\include\win32" "%~2" /Fe:"%~3" /Fo:"%~4"
exit /b %errorlevel%
