@echo off
REM 以 HotswapAgent 启动 eslink-core Hub（支持热更新）
set "JAVA_HOME=C:\Program Files\Zulu\zulu-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d D:\ESLink_neoforged\EtherSync-Trinity
"%JAVA_HOME%\bin\java.exe" -javaagent:D:\ESLink_neoforged\EtherSync-Trinity\tools\hotswap-agent\hotswap-agent-2.0.0.jar -jar eslink-core-0.1.0.jar --server --port 3307 --key "@TyphonDPS799"
pause
