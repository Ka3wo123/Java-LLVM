@echo off
setlocal

set JAVA_FX="C:\Program Files\Java\javafx-sdk-17.0.11"
set JAVA_FX_LIBS=%JAVA_FX%\lib
set SRC_DIR=..\tests\in\
set ANTLR_LIB="C:\Program Files\Java\libs\antlr-4.9.3-complete.jar"

if "%1" == "compile" goto compile
if "%1" == "run" goto run
if "%1" == "clean" goto clean
if "%1" == "draw" goto draw


:default
echo Invalid target. Use compile, run or clean.

goto end

:compile
java -cp %ANTLR_LIB% org.antlr.v4.Tool JavaLexer.g4 -visitor
java -cp %ANTLR_LIB% org.antlr.v4.Tool JavaParser.g4 -visitor

rmdir /s /q ..\build
mkdir ..\build

javac --module-path %JAVA_FX_LIBS% --add-modules javafx.controls,javafx.fxml Main.java -d ..\build

if errorlevel 1 (
    echo Compilation failed.
    goto end
)

echo Compilation successful.

goto end

:run
java --module-path %JAVA_FX_LIBS% --add-modules javafx.controls,javafx.fxml -cp %ANTLR_LIB%;..\build Main %SRC_DIR%\%2
goto end

:clean
rmdir /s /q ..\build
del *Parse*.java *Lexer*.java syntaxtree visitor *.tokens *.interp JavaCharStream.java *.class out*
goto end

:draw
java -cp %ANTLR_LIB%;..\build org.antlr.v4.gui.TestRig Java goal %SRC_DIR%\%2 -gui
goto end

:end
endlocal
