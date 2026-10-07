# Fetch the Google Tensor NPU dispatch shim into app/src/main/jniLibs/arm64-v8a/.
#
# A Tensor-G5 AOT graph is executed by a dispatch shim that dlopen()s the
# device's own EdgeTPU runtime. The shim is NOT in the LiteRT Maven AAR -- the
# AAR ships only libLiteRt.so and the OpenCL accelerator -- it comes from the
# `litert_npu_runtime_libraries.zip` asset on the matching LiteRT GitHub
# release. The shim, the Android `litert` runtime and the AOT compiler must all
# be the same LiteRT release, or the model fails to initialize (or silently
# misses the NPU). This app pins litert:2.2.0 (see gradle/libs.versions.toml).
#
# The .so is gitignored (`**/jniLibs/**/*.so`) because runtime libraries are
# fetched, not committed.
param(
    [string]$Version = 'v2.2.0'
)

$ErrorActionPreference = 'Stop'
$dest = Join-Path $PSScriptRoot '..\app\src\main\jniLibs\arm64-v8a'
$url = "https://github.com/google-ai-edge/LiteRT/releases/download/$Version/litert_npu_runtime_libraries.zip"

$temp = Join-Path ([System.IO.Path]::GetTempPath()) ("pockettts-dispatch-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $temp | Out-Null
try {
    $zip = Join-Path $temp 'libs.zip'
    Write-Host "fetch $url"
    Invoke-WebRequest -Uri $url -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $temp -Force
    $src = Join-Path $temp 'google_tensor_runtime\src\main\jni\arm64-v8a\libLiteRtDispatch_GoogleTensor.so'
    if (-not (Test-Path $src)) { throw "dispatch shim was not found in $url" }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item $src (Join-Path $dest 'libLiteRtDispatch_GoogleTensor.so') -Force
    Write-Host "wrote $dest\libLiteRtDispatch_GoogleTensor.so"
} finally {
    Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
}
