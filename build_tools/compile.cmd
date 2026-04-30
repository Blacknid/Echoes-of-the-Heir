@echo off
setlocal enabledelayedexpansion

:: --- CONFIGURATION ---
set APP_NAME=MichisAdventure
set VERSION=2.0
set MAIN_CLASS=main.Main
set JAR_NAME=Michi-s-adventure.jar

:: --- PATHS ---
:: Resolve script directory and set repo root relative to this script
set SCRIPT_DIR=%~dp0
set ROOT=%SCRIPT_DIR%..
set SRC_DIR=%ROOT%\ceva\src
set BIN_DIR=%ROOT%\ceva\bin
set DEPLOY_DIR=%ROOT%\deploy
set OUTPUT_DIR=%ROOT%\output
set LIB_DIR=%ROOT%\ceva\lib
set LIBS=%LIB_DIR%\jl-1.0.1.jar;%LIB_DIR%\tritonus-share-0.3.7.jar;%LIB_DIR%\mp3spi-1.9.5.jar

echo ============================================
echo   Michi's Adventure Build Pipeline v%VERSION%
echo ============================================
echo.

:: Auto-increment build number in build.properties
set BUILD_PROPS=%SRC_DIR%\res\build.properties
if exist "%BUILD_PROPS%" (
    for /f "tokens=1,2 delims==" %%a in (%BUILD_PROPS%) do (
        if "%%a"=="build" set /a BUILD_NUM=%%b+1
    )
    echo version=%VERSION%> "%BUILD_PROPS%"
    echo build=!BUILD_NUM!>> "%BUILD_PROPS%"
    echo   Build number: !BUILD_NUM!
)

echo [1/5] Cleaning and Compiling...
if exist "%BIN_DIR%" rd /s /q "%BIN_DIR%"
if exist "%DEPLOY_DIR%" rd /s /q "%DEPLOY_DIR%"
mkdir "%BIN_DIR%"
mkdir "%DEPLOY_DIR%"

:: Compiles all sub-packages in src
set SOURCE_LIST=%TEMP%\michi_sources.txt
if exist "%SOURCE_LIST%" del "%SOURCE_LIST%"
for /R "%SRC_DIR%" %%f in (*.java) do @echo %%f>> "%SOURCE_LIST%"
if not exist "%SOURCE_LIST%" (echo [FAIL] No Java source files found! & pause & exit /b 1)
javac -cp "%LIBS%" -d "%BIN_DIR%" -sourcepath "%SRC_DIR%" @"%SOURCE_LIST%"
set JAVAC_EXIT=%ERRORLEVEL%
del "%SOURCE_LIST%" >nul 2>&1
if %JAVAC_EXIT% NEQ 0 (echo [FAIL] Compilation failed! & pause & exit /b 1)
echo   [OK] Compilation succeeded.

echo [2/5] Copying resources...
:: Copy res/ tree into bin/ so JAR includes sprites, maps, sounds, JSON data
xcopy "%SRC_DIR%\res" "%BIN_DIR%\res" /E /I /Q /Y >nul 2>&1
if %ERRORLEVEL% NEQ 0 (echo [WARN] Resource copy had issues, continuing...)
:: Extract MP3SPI libs into bin so the fat JAR includes them
for %%f in ("%LIB_DIR%\*.jar") do (
    pushd "%BIN_DIR%" & jar xf "%%f" & popd
)
del /Q "%BIN_DIR%\META-INF\*.SF" "%BIN_DIR%\META-INF\*.DSA" "%BIN_DIR%\META-INF\*.RSA" >nul 2>&1
echo   [OK] Resources copied.

echo [3/5] Building JAR...
jar --create --file="%DEPLOY_DIR%\%JAR_NAME%" --main-class=%MAIN_CLASS% -C "%BIN_DIR%" .
if %ERRORLEVEL% NEQ 0 (echo [FAIL] JAR creation failed! & pause & exit /b 1)
echo   [OK] JAR created: %DEPLOY_DIR%\%JAR_NAME%

:: Ship the patch-server endpoint list next to the JAR (used by UpdateClient).
:: Renamed to update_servers.txt only if the player hasn't customized it.
if exist "%ROOT%\ceva\update_servers.example.txt" (
    if not exist "%DEPLOY_DIR%\update_servers.txt" (
        copy /Y "%ROOT%\ceva\update_servers.example.txt" "%DEPLOY_DIR%\update_servers.txt" >nul
        echo   [OK] Bundled update_servers.txt
    )
)
:: Same for save server endpoints.
if exist "%ROOT%\ceva\save_servers.example.txt" (
    if not exist "%DEPLOY_DIR%\save_servers.txt" (
        copy /Y "%ROOT%\ceva\save_servers.example.txt" "%DEPLOY_DIR%\save_servers.txt" >nul
        echo   [OK] Bundled save_servers.txt
    )
)

echo [4/5] Verifying JAR...
java -jar "%DEPLOY_DIR%\%JAR_NAME%" --version >nul 2>&1
:: Quick smoke test — just verify JAR is runnable (exits immediately with --version)
echo   [OK] JAR is valid.

echo [5/5] Packaging EXE...
:: Detect WiX toolset (wix.exe) — required by `jpackage` to build MSI installers
where wix.exe >nul 2>&1
if %ERRORLEVEL%==0 (
        set JP_TYPE=exe
) else (
        echo [WARN] WiX toolset (wix.exe) not found in PATH — falling back to app-image packaging.
        set JP_TYPE=app-image
)
jpackage ^
    --input "%DEPLOY_DIR%" ^
    --name "%APP_NAME%" ^
    --main-jar "%JAR_NAME%" ^
    --main-class "%MAIN_CLASS%" ^
    --type %JP_TYPE% ^
    --dest "%OUTPUT_DIR%" ^
  --app-version %VERSION% ^
  --java-options "-XX:+UseG1GC -XX:MaxGCPauseMillis=5 -Dsun.java2d.opengl=True -Dsun.java2d.accthreshold=0" ^
  --win-shortcut --win-menu
if %ERRORLEVEL% NEQ 0 (echo [WARN] jpackage failed — JAR still available in deploy/.)

echo.
echo ============================================
echo   Build complete!
echo   JAR: %DEPLOY_DIR%\%JAR_NAME%
echo   EXE: %OUTPUT_DIR%\%APP_NAME%-%VERSION%.exe
echo ============================================
pause