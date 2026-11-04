@echo off
setlocal
set JAVACMD=java.exe
if defined JAVA_HOME set "JAVACMD=%JAVA_HOME%\bin\java.exe"
"%JAVACMD%" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
