@echo off
echo --------------------------------------
echo FlatChecker startup script for Windows
echo Type Ctrl-C to quit
echo --------------------------------------
echo.
echo Checking for Java...
echo.
call java -version
IF ERRORLEVEL 1 GOTO nojava
echo.
echo Setting path...
set PATH=%PATH%;%CD%
call java -jar flatcheck.jar
GOTO noerror
:nojava
echo.
echo It seems that Java RTE is not installed on your computer.
echo Please download and install it from https://java.com/en/download/
echo Then run this script again
echo.
:noerror
pause
