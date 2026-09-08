#!/usr/bin/env bash
set -euo pipefail
# RTKLIB 원본을 그대로 컴파일한다. 기존 AFS ABI와 새 PVT ABI는 같은 DLL에 있다.
x86_64-w64-mingw32-gcc -O2 -shared -static-libgcc -DWIN32 \
  -ffunction-sections -fdata-sections -Wl,--gc-sections \
  -I. -Irtk -Ildpc -Ipocketsdr \
  lnis_afs_codec.c lnis_pvt.c afs_nav.c pocketsdr/pocketsdr.c \
  rtk/rtkcmn.c rtk/rcvraw.c rtk/pntpos.c rtk/ephemeris.c \
  rtk/preceph.c rtk/sbas.c rtk/ionex.c \
  libsdr.a libldpc.a -lwinmm -lws2_32 -lm -o LnisAfsCodec.dll
# 합성 항법/관측값으로 해 생성과 독립 컨텍스트 재현성을 확인하는 별도 시험 실행 파일이다.
x86_64-w64-mingw32-gcc -O2 -DWIN32 -ffunction-sections -fdata-sections -Wl,--gc-sections \
  -I. -Irtk test_pvt.c rtk/rtkcmn.c rtk/rcvraw.c rtk/pntpos.c rtk/ephemeris.c \
  rtk/preceph.c rtk/sbas.c rtk/ionex.c -lwinmm -lws2_32 -lm -o test_pvt.exe
