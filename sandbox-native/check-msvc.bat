@echo off
setlocal

set "VSDEVCMD=%ProgramFiles(x86)%\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\VsDevCmd.bat"
if not exist "%VSDEVCMD%" (
  echo Visual Studio 2022 C++ Build Tools are required. 1>&2
  exit /b 1
)

call "%VSDEVCMD%" -arch=x64 >nul
if errorlevel 1 exit /b %errorlevel%

set "PATH=%USERPROFILE%\.cargo\bin;%PATH%"
if /i "%~1"=="test" (
  cargo test --offline %2 %3 %4 %5 %6 %7 %8 %9
  exit /b %errorlevel%
)
if /i "%~1"=="build" (
  cargo build --offline %2 %3 %4 %5 %6 %7 %8 %9
  exit /b %errorlevel%
)
cargo check --offline %*
