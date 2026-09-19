<#
.SYNOPSIS
    Generates src/main/resources/HandRanks.dat, the Two Plus Two hand-rank lookup table.

.DESCRIPTION
    Evaluator reads this 130 MB table as a classpath resource. It used to be stored in Git LFS,
    but LFS is disabled on the repository, so it is generated locally instead and kept out of
    git. The generator needs only a JDK 11 or newer, and checks its own output against the
    sha256 of the canonical table before reporting success.

    The sbt build runs this generator automatically before compiling; use this script when you
    want the table without going through sbt, for example when working in the IDE.

.PARAMETER Output
    Where to write the table. Defaults to src/main/resources/HandRanks.dat.

.PARAMETER Force
    Regenerate even if a correctly sized table is already there.

.EXAMPLE
    .\tools\generate-handranks.ps1
#>
[CmdletBinding()]
param(
    [string] $Output,
    [switch] $Force
)

$ErrorActionPreference = 'Stop'

$expectedBytes = 129951336
$repoRoot = Split-Path -Parent $PSScriptRoot
$generator = Join-Path $PSScriptRoot 'HandRankTableGenerator.java'

if (-not $Output) {
    $Output = Join-Path $repoRoot 'src\main\resources\HandRanks.dat'
}

if ((-not $Force) -and (Test-Path $Output) -and ((Get-Item $Output).Length -eq $expectedBytes)) {
    Write-Host "HandRanks.dat is already in place at $Output"
    Write-Host 'Pass -Force to regenerate it.'
    exit 0
}

# Prefer JAVA_HOME, then java on PATH, then the JDKs IntelliJ keeps in ~/.jdks.
$java = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path $candidate) { $java = $candidate }
}
if (-not $java) {
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) { $java = $onPath.Source }
}
if (-not $java) {
    $jdks = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path $jdks) {
        # Running a .java file straight from source needs the compiler, so skip any
        # runtime that ships without javac.
        $java = Get-ChildItem $jdks -Directory |
            ForEach-Object { Join-Path $_.FullName 'bin\java.exe' } |
            Where-Object { (Test-Path $_) -and (Test-Path (Join-Path (Split-Path $_) 'javac.exe')) } |
            Sort-Object -Descending |
            Select-Object -First 1
    }
}
if (-not $java) {
    throw 'No JDK found. Set JAVA_HOME, or put java on PATH (JDK 11 or newer is required).'
}

Write-Host "Using $java"
Write-Host "Generating $Output ..."
& $java '-Xmx1500m' $generator $Output
if ($LASTEXITCODE -ne 0) {
    throw "Generator failed with exit code $LASTEXITCODE"
}
