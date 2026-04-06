@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Default to the repo-local .gradle directory so wrapper runs reuse the
@rem project's existing Gradle user home instead of creating a sibling directory.
@rem Set SPEC_CODE_PRESERVE_GRADLE_USER_HOME=true to keep an inherited value.
if /I not "%SPEC_CODE_PRESERVE_GRADLE_USER_HOME%"=="true" set "GRADLE_USER_HOME=%APP_HOME%\.gradle"

set "SPEC_CODE_REQUIRED_JAVA_MAJOR=21"
call :ensureSpecCodeJavaHome

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
@rem Setup the command line

call :tryUseInstalledGradle %*
if not "%ERRORLEVEL%"=="255" goto end



@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
goto :eof

:tryUseInstalledGradle
if /I "%SPEC_CODE_PREFER_WRAPPER_DOWNLOAD%"=="true" exit /b 255

set "SPEC_CODE_LOCAL_GRADLE="

@rem Keep this in sync with gradle/wrapper/gradle-wrapper.properties.
if defined ProgramFiles if exist "%ProgramFiles%\gradle-9.3.1\bin\gradle.bat" (
    set "SPEC_CODE_LOCAL_GRADLE=%ProgramFiles%\gradle-9.3.1\bin\gradle.bat"
)

if not defined SPEC_CODE_LOCAL_GRADLE exit /b 255

cmd /d /c ""%SPEC_CODE_LOCAL_GRADLE%" %*"
exit /b %ERRORLEVEL%

:ensureSpecCodeJavaHome
set "SPEC_CODE_JAVA_VERSION_FILE=%TEMP%\spec-code-java-version-check.tmp"

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        call "%JAVA_HOME%\bin\java.exe" -version 1>"%SPEC_CODE_JAVA_VERSION_FILE%" 2>&1
        findstr /C:%SPEC_CODE_REQUIRED_JAVA_MAJOR%. "%SPEC_CODE_JAVA_VERSION_FILE%" >nul 2>nul
        if not errorlevel 1 (
            if exist "%SPEC_CODE_JAVA_VERSION_FILE%" del /q "%SPEC_CODE_JAVA_VERSION_FILE%"
            goto :eof
        )
    )
)

if defined ProgramFiles if exist "%ProgramFiles%\JetBrains" (
    for /d %%D in ("%ProgramFiles%\JetBrains\*") do (
        if exist "%%~fD\jbr\bin\java.exe" (
            call "%%~fD\jbr\bin\java.exe" -version 1>"%SPEC_CODE_JAVA_VERSION_FILE%" 2>&1
            findstr /C:%SPEC_CODE_REQUIRED_JAVA_MAJOR%. "%SPEC_CODE_JAVA_VERSION_FILE%" >nul 2>nul
            if not errorlevel 1 (
                if exist "%SPEC_CODE_JAVA_VERSION_FILE%" del /q "%SPEC_CODE_JAVA_VERSION_FILE%"
                set "JAVA_HOME=%%~fD\jbr"
                goto :eof
            )
        )
    )
)

if defined ProgramFiles if exist "%ProgramFiles%\Java" (
    for /d %%D in ("%ProgramFiles%\Java\*") do (
        if exist "%%~fD\bin\java.exe" (
            call "%%~fD\bin\java.exe" -version 1>"%SPEC_CODE_JAVA_VERSION_FILE%" 2>&1
            findstr /C:%SPEC_CODE_REQUIRED_JAVA_MAJOR%. "%SPEC_CODE_JAVA_VERSION_FILE%" >nul 2>nul
            if not errorlevel 1 (
                if exist "%SPEC_CODE_JAVA_VERSION_FILE%" del /q "%SPEC_CODE_JAVA_VERSION_FILE%"
                set "JAVA_HOME=%%~fD"
                goto :eof
            )
        )
    )
)

if defined GRADLE_USER_HOME if exist "%GRADLE_USER_HOME%\caches" (
    for /d /r "%GRADLE_USER_HOME%\caches" %%D in (jbr) do (
        if exist "%%~fD\bin\java.exe" (
            call "%%~fD\bin\java.exe" -version 1>"%SPEC_CODE_JAVA_VERSION_FILE%" 2>&1
            findstr /C:%SPEC_CODE_REQUIRED_JAVA_MAJOR%. "%SPEC_CODE_JAVA_VERSION_FILE%" >nul 2>nul
            if not errorlevel 1 (
                if exist "%SPEC_CODE_JAVA_VERSION_FILE%" del /q "%SPEC_CODE_JAVA_VERSION_FILE%"
                set "JAVA_HOME=%%~fD"
                goto :eof
            )
        )
    )
)

if exist "%SPEC_CODE_JAVA_VERSION_FILE%" del /q "%SPEC_CODE_JAVA_VERSION_FILE%"

goto :eof
