# Copy this file to tools/mc.local.ps1 (git-ignored) and fill in local values.
# Command-line parameters override these values.
# Environment variables may also be used directly.

$env:VLA_MC_VERSION_DIR = '<path-to-minecraft-version-dir>'
$env:VLA_MC_WORLD       = 'vla-m1'
$env:VLA_MC_PORT        = '25580'

# Optional:
# $env:VLA_MC_JAVA          = '<path-to-java.exe>'
# $env:VLA_MC_WORLD_SOURCE  = '<path-to-a-save-directory>'
# $env:VLA_MC_ASSETS_DIR    = '<path-to-assets>'
# $env:VLA_MC_LIBRARIES_DIR = '<path-to-libraries>'
