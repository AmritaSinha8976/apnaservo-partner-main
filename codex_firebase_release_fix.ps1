$ErrorActionPreference = "Stop"

$projectRoot = "E:\New project\.vscode\ApnaServo\partner-app"
$incomingJson = "C:\Users\Admin\Downloads\google-services (3).json"
$appJson = Join-Path $projectRoot "app\google-services.json"
$groovyGradle = Join-Path $projectRoot "app\build.gradle"
$kotlinGradle = Join-Path $projectRoot "app\build.gradle.kts"
$sourceAab = Join-Path $projectRoot "app\build\outputs\bundle\release\app-release.aab"
$finalAab = Join-Path $projectRoot "ApnaServo-Partner-Firebase-Fixed.aab"
$buildLog = Join-Path $projectRoot "firebase-release-build.log"
$successFile = Join-Path $projectRoot "FIREBASE_RELEASE_SUCCESS.txt"
$failureFile = Join-Path $projectRoot "FIREBASE_RELEASE_FAILED.txt"
$expectedPackage = "com.apnaservo.partner"

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$jsonBackup = $null
$gradleBackup = $null
$gradleFile = $null
$pushed = $false

Remove-Item -LiteralPath $successFile -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $failureFile -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $finalAab -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $buildLog -Force -ErrorAction SilentlyContinue

try {
    if (-not (Test-Path -LiteralPath $projectRoot -PathType Container)) {
        throw "Partner project was not found at the approved E-drive path."
    }
    if (-not (Test-Path -LiteralPath $incomingJson -PathType Leaf)) {
        throw "The attached google-services (3).json file was not found."
    }

    $firebase = Get-Content -LiteralPath $incomingJson -Raw | ConvertFrom-Json
    $matchingClients = @(
        $firebase.client | Where-Object {
            $_.client_info.android_client_info.package_name -eq $expectedPackage
        }
    )
    if ($matchingClients.Count -eq 0) {
        throw "The supplied Firebase JSON does not contain package $expectedPackage."
    }

    $androidOauthClients = @(
        foreach ($client in $matchingClients) {
            $client.oauth_client | Where-Object {
                $_.client_type -eq 1 -and
                $_.android_info.package_name -eq $expectedPackage -and
                -not [string]::IsNullOrWhiteSpace($_.android_info.certificate_hash)
            }
        }
    )
    $certificateHashes = @(
        $androidOauthClients |
            ForEach-Object { $_.android_info.certificate_hash.ToUpperInvariant() } |
            Sort-Object -Unique
    )
    if ($certificateHashes.Count -eq 0) {
        throw "The supplied Firebase JSON has no Android OAuth SHA-1 client for $expectedPackage."
    }

    if (Test-Path -LiteralPath $groovyGradle -PathType Leaf) {
        $gradleFile = $groovyGradle
    } elseif (Test-Path -LiteralPath $kotlinGradle -PathType Leaf) {
        $gradleFile = $kotlinGradle
    } else {
        throw "Partner app Gradle configuration was not found."
    }

    $gradleText = [System.IO.File]::ReadAllText($gradleFile)
    $packageMatch = [regex]::Match(
        $gradleText,
        '(?m)^\s*(?:applicationId|namespace)\s*(?:=\s*)?["'']([^"'']+)["'']'
    )
    if ($packageMatch.Success -and $packageMatch.Groups[1].Value -ne $expectedPackage) {
        throw "Gradle package is $($packageMatch.Groups[1].Value), expected $expectedPackage."
    }

    # Support both a literal versionCode and the CI-overridable form used by
    # app/build.gradle:
    # versionCode = (project.findProperty("VERSION_CODE") ?: "2")...
    $versionCodeRegex = New-Object System.Text.RegularExpressions.Regex(
        '(?m)^(\s*versionCode\s*(?:=\s*)?(?:(?:\(project\.findProperty\(["'']VERSION_CODE["'']\)\s*\?:\s*["''])?))(\d+)((?:["'']\).*)?.*)$'
    )
    $versionCodeMatch = $versionCodeRegex.Match($gradleText)
    if (-not $versionCodeMatch.Success) {
        throw "versionCode was not found in the Partner app Gradle file."
    }
    $oldVersionCode = [int]$versionCodeMatch.Groups[2].Value
    $newVersionCode = $oldVersionCode + 1
    $newVersionCodeLine =
        $versionCodeMatch.Groups[1].Value +
        $newVersionCode +
        $versionCodeMatch.Groups[3].Value
    $gradleText =
        $gradleText.Substring(0, $versionCodeMatch.Index) +
        $newVersionCodeLine +
        $gradleText.Substring($versionCodeMatch.Index + $versionCodeMatch.Length)

    # Likewise, increment the fallback VERSION_NAME without removing the
    # ability for CI/Gradle properties to override it.
    $versionNameRegex = New-Object System.Text.RegularExpressions.Regex(
        '(?m)^(\s*versionName\s*(?:=\s*)?(?:(?:\(project\.findProperty\(["'']VERSION_NAME["'']\)\s*\?:\s*)?["'']))([^"'']+)(["''](?:\).*)?.*)$'
    )
    $versionNameMatch = $versionNameRegex.Match($gradleText)
    if (-not $versionNameMatch.Success) {
        throw "versionName was not found in the Partner app Gradle file."
    }
    $oldVersionName = $versionNameMatch.Groups[2].Value
    $versionParts = $oldVersionName -split '\.'
    if ($versionParts.Count -ge 3 -and $versionParts[-1] -match '^\d+$') {
        $versionParts[-1] = ([int]$versionParts[-1] + 1).ToString()
        $newVersionName = $versionParts -join '.'
    } elseif ($versionParts.Count -eq 2 -and $versionParts[-1] -match '^\d+$') {
        $newVersionName = "$oldVersionName.1"
    } else {
        $newVersionName = "$oldVersionName-firebase-fix"
    }
    $newVersionNameLine =
        $versionNameMatch.Groups[1].Value +
        $newVersionName +
        $versionNameMatch.Groups[3].Value
    $gradleText =
        $gradleText.Substring(0, $versionNameMatch.Index) +
        $newVersionNameLine +
        $gradleText.Substring($versionNameMatch.Index + $versionNameMatch.Length)

    $backupRoot = Join-Path ([System.IO.Path]::GetTempPath()) (
        "apnaservo_partner_firebase_" + [Guid]::NewGuid().ToString("N")
    )
    New-Item -ItemType Directory -Path $backupRoot | Out-Null
    if (Test-Path -LiteralPath $appJson -PathType Leaf) {
        $jsonBackup = Join-Path $backupRoot "google-services.json"
        Copy-Item -LiteralPath $appJson -Destination $jsonBackup -Force
    }
    $gradleBackup = Join-Path $backupRoot ([System.IO.Path]::GetFileName($gradleFile))
    Copy-Item -LiteralPath $gradleFile -Destination $gradleBackup -Force

    Copy-Item -LiteralPath $incomingJson -Destination $appJson -Force
    [System.IO.File]::WriteAllText($gradleFile, $gradleText, $utf8NoBom)

    Push-Location $projectRoot
    $pushed = $true
    & ".\gradlew.bat" clean bundleRelease --console=plain *> $buildLog
    $buildExitCode = $LASTEXITCODE
    Pop-Location
    $pushed = $false

    if ($buildExitCode -ne 0) {
        throw "Gradle bundleRelease failed. See firebase-release-build.log."
    }
    if (-not (Test-Path -LiteralPath $sourceAab -PathType Leaf)) {
        throw "Gradle completed but the release AAB was not generated."
    }

    Copy-Item -LiteralPath $sourceAab -Destination $finalAab -Force
    $aabInfo = Get-Item -LiteralPath $finalAab
    if ($aabInfo.Length -le 0) {
        throw "Generated AAB is empty."
    }

    $javaHome = $env:JAVA_HOME
    $jarsigner = $null
    if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
        $candidate = Join-Path $javaHome "bin\jarsigner.exe"
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $jarsigner = $candidate
        }
    }
    if ($null -eq $jarsigner) {
        $jarsignerCommand = Get-Command jarsigner.exe -ErrorAction SilentlyContinue
        if ($null -ne $jarsignerCommand) {
            $jarsigner = $jarsignerCommand.Source
        }
    }
    if ($null -ne $jarsigner) {
        & $jarsigner -verify $finalAab *> $null
        if ($LASTEXITCODE -ne 0) {
            throw "Generated AAB signature verification failed."
        }
    }

    $aabSha256 = (Get-FileHash -LiteralPath $finalAab -Algorithm SHA256).Hash
    $resultLines = @(
        "SUCCESS"
        "Project: $projectRoot"
        "Package: $expectedPackage"
        "Firebase Android OAuth SHA-1 entries: $($certificateHashes.Count)"
        "Version code: $oldVersionCode -> $newVersionCode"
        "Version name: $oldVersionName -> $newVersionName"
        "AAB: $finalAab"
        "AAB size: $($aabInfo.Length) bytes"
        "AAB SHA-256: $aabSha256"
        "Built at: $([DateTimeOffset]::Now.ToString('o'))"
    )
    [System.IO.File]::WriteAllLines($successFile, $resultLines, $utf8NoBom)
} catch {
    if ($pushed) {
        Pop-Location
    }

    if ($null -ne $gradleBackup -and (Test-Path -LiteralPath $gradleBackup -PathType Leaf)) {
        Copy-Item -LiteralPath $gradleBackup -Destination $gradleFile -Force
    }
    if ($null -ne $jsonBackup -and (Test-Path -LiteralPath $jsonBackup -PathType Leaf)) {
        Copy-Item -LiteralPath $jsonBackup -Destination $appJson -Force
    }

    $failureLines = @(
        "FAILED"
        "Project: $projectRoot"
        "Reason: $($_.Exception.Message)"
        "Build log: $buildLog"
        "Failed at: $([DateTimeOffset]::Now.ToString('o'))"
    )
    [System.IO.File]::WriteAllLines($failureFile, $failureLines, $utf8NoBom)
    exit 1
}

exit 0
