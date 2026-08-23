# Native AFS codec

`bin/win-x64/LnisAfsCodec.dll` is the ABI v1 codec used by the Windows agent through JNA.
The C ABI header and wrapper source are retained here for traceability. The implementation links code from the
LANS-AFS-SIM and PocketSDR-AFS trees; review `THIRD-PARTY-NOTICES.txt` before redistribution.

To rebuild the DLL, use the original WPF repository's `Native/LnisAfsCodec/build-wsl.ps1`, then replace the DLL in
this directory and run the Java golden-vector tests before deployment.

