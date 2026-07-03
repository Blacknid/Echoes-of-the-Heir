@echo off
:: Run Michi's Adventure from compiled classes with optimized JVM flags.
:: Usage: run.cmd  (from build_tools/ folder, or double-click)

set ROOT=%~dp0..
set BIN_DIR=%ROOT%\ceva\bin

echo Starting Michi's Adventure (dev)...
java -server ^
  -Xms256m -Xmx512m ^
  -XX:+UseG1GC -XX:MaxGCPauseMillis=16 -XX:+ParallelRefProcEnabled ^
  -Dsun.java2d.opengl=True -Dsun.java2d.accthreshold=0 ^
  -cp "%BIN_DIR%" main.Main
