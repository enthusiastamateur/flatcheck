@echo off
echo --------------------------------------
echo FlatChecker startup script for Windows
echo Type Ctrl-C to quit
echo --------------------------------------
echo Setting path...
set PATH=%PATH%;%CD%1
call java -jar flatcheck.jar