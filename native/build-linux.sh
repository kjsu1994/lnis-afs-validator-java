#!/usr/bin/env bash
set -euo pipefail

# 원본 복사본을 준비한 빌드 디렉터리에서만 실행한다. Windows 배포 DLL은 변경하지 않는다.
# 필요한 개발 패키지: gcc, libc6-dev, libfftw3-dev, libusb-1.0-0-dev
cc="${CC:-gcc}"
flags=(-O2 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections
       -I. -Irtk -Ildpc -Ipocketsdr -pthread '-DRAND_FILE="./randfile"')
rtk=(rtk/rtkcmn.c rtk/rcvraw.c rtk/pntpos.c rtk/ephemeris.c
     rtk/preceph.c rtk/sbas.c rtk/ionex.c)
ldpc=()
for name in rcode channel dec enc alloc intio blockio check open mod2dense mod2sparse mod2convert distrib rand; do
    ldpc+=("ldpc/$name.c")
done

# -z defs: 미해결 심볼을 가진 .so가 만들어져 런타임에 뒤늦게 실패하지 않도록 한다.
"$cc" "${flags[@]}" -shared -Wl,--gc-sections,-z,defs \
    lnis_afs_codec.c lnis_pvt.c afs_nav.c pocketsdr/pocketsdr.c sdr_ldpc_afs.c \
    "${rtk[@]}" "${ldpc[@]}" -lm -o libLnisAfsCodec.so
"$cc" "${flags[@]}" -Wl,--gc-sections test_pvt.c "${rtk[@]}" -lm -o test_pvt
./test_pvt
"$cc" -O2 -I. test_afs.c -L. -lLnisAfsCodec '-Wl,-rpath,$ORIGIN' -o test_afs
./test_afs
