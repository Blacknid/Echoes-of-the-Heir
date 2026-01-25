@echo off
setlocal

:: --- CONFIGURATION ---
set APP_NAME=MichisAdventure
set VERSION=1.0.0
set MAIN_CLASS=main.Main
set JAR_NAME=Michi-s-adventure.jar

:: --- PATHS ---
set ROOT=..
set SRC_DIR=%ROOT%\src
set BIN_DIR=%ROOT%\bin
set DEPLOY_DIR=%ROOT%\deploy
set OUTPUT_DIR=%ROOT%\output

echo [1/3] Cleaning and Compiling...
if exist "%BIN_DIR%" rd /s /q "%BIN_DIR%"
if exist "%DEPLOY_DIR%" rd /s /q "%DEPLOY_DIR%"
mkdir "%BIN_DIR%"
mkdir "%DEPLOY_DIR%"

:: Compiles all sub-packages in src
javac -d "%BIN_DIR%" -sourcepath "%SRC_DIR%" %SRC_DIR%\main\*.java
if %ERRORLEVEL% NEQ 0 (echo Compilation failed! & pause & exit /b)

echo [2/3] Building JAR...
jar --create --file="%DEPLOY_DIR%\%JAR_NAME%" --main-class=%MAIN_CLASS% -C "%BIN_DIR%" .

echo [3/3] Packaging EXE...
jpackage ^
  --input "%DEPLOY_DIR%" ^
  --name "%APP_NAME%" ^
  --main-jar "%JAR_NAME%" ^
  --main-class "%MAIN_CLASS%" ^
  --type exe ^
  --dest "%OUTPUT_DIR%" ^
  --app-version %VERSION% ^
  --win-shortcut --win-menu

echo Done!
pause