# TripAgent Nexus - Spring API launcher (Windows native, port 8082)
# Loads .env from project root into process env, then starts Spring Boot jar.
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$env:JAVA_HOME = Join-Path $root "..\..\.devtools\jdk21\jdk-21.0.12.1+1"
$java = Join-Path $env:JAVA_HOME "bin\java.exe"
$jar = Get-ChildItem (Join-Path $root "apps\trip-agent-api\target\trip-agent-api-*.jar") |
    Where-Object { $_.Name -notlike "*.original" } |
    Select-Object -First 1 -ExpandProperty FullName

Get-Content (Join-Path $root ".env") | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
        $idx = $line.IndexOf("=")
        $k = $line.Substring(0, $idx).Trim()
        $v = $line.Substring($idx + 1).Trim()
        Set-Item -Path ("Env:" + $k) -Value $v
    }
}

$env:SERVER_PORT = "8082"
$env:SPRING_PROFILES_ACTIVE = "local"

Set-Location $root
Write-Output ("Starting Spring API: " + $jar + " on port 8082")
& $java -Xms256m -Xmx1024m "-Dfile.encoding=UTF-8" -jar $jar
