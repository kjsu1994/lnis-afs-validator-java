# PVT 네이티브 연결 경계

기존 AFS DLL에 PVT 확장을 추가한 구조와 원본 사용 범위를 기록한다.

현재 입력 어댑터는 GPS L1 C/A를 지원한다. 다중 GNSS 전체 지원은 포함하지 않는다.

## 원본 유지 원칙

- 기존 lnis_afs_* ABI와 AFS 부호화/복호화 동작은 유지한다.
- 같은 LnisAfsCodec.dll에 lnis_pvt_* 인터페이스를 추가한다.
- PocketSDR-AFS의 src/sdr_pvt.c 일반 GNSS 경로와 내부 RTKLIB의 pntpos()를 기준으로 한다.
- 달 환경용 sdr_pvt_afs.c는 이번 지구 GNSS 전달 시험에 사용하지 않는다.
- 원본 파일명, 함수명, 포맷을 유지하고 장치 입력 변환과 ABI 래퍼는 별도 파일에 둔다.
- 원본 변경이 필요한 경우 원본 해시, 파일, 변경 이유와 diff를 기록한다.

## 직접 작성할 부분

PocketSDR의 RTKLIB rcvraw.c에는 2024/04/03 이력으로 수신기별 함수를 제거했다고 명시되어 있다.
헤더에 input_ubx() 선언이 있어도 구현이 포함되어 있다고 가정해서는 안 된다.
RAWX 관측값과 SFRBX 항법 메시지를 obsd_t/nav_t와 원본 항법 해석 함수에 연결하는 어댑터가 필요하다.
위성군별 메시지 해석을 확인한 뒤 지원 범위를 명시한다.

## 계산 재현성

- 양쪽은 동일한 관측 시각, 항법 갱신 순서와 계산 설정을 사용한다.
- DTN 도착 시각이나 현재 PC 시각으로 관측 시각을 대체하지 않는다.
- 외부 항법 캐시를 암묵적으로 읽지 않고 초기 상태를 재현한다.
- 측위 불가와 좌표 0, 속도 계산 불가와 유효 속도 0을 구분한다.
- 기준 PVT는 DTN payload에 넣지 않는다. Receiver는 복원 입력으로 독립 계산한다.
- PVT 일치는 전달 전후 계산의 일치성이며 절대 위치 정확도를 증명하지 않는다.

## 빌드와 검증

native/build-pvt.ps1은 원본을 임시 폴더로 복사해 build/native-pvt/LnisAfsCodec.dll을 만든다.
기존 native/bin/win-x64 DLL을 자동 교체하지 않는다.
RTKLIB 원본 C 파일은 수정하지 않고 컴파일한다. source 해시는 빌드 결과의 upstream-sha256.csv에 있다.
현재 로컬 LANS 사본은 문자열 따옴표가 손상된 로그 함수가 있으므로 임시 복사본의 로그 함수만 제외한다.
이 처리는 기존 WPF 빌드와 동일하며 원본 폴더에는 쓰지 않는다.

lnis_pvt.c는 직접 작성한 입력/ABI 어댑터이며 pntpos와 decode_frame을 호출한다.
관측 주차로 GPS week rollover를 보정하고 위치·속도 유효성을 구분한다.
GPS 전용이므로 sdr_pvt.c의 비GPS 시간계 fallback은 사용하지 않는다.
포함한 RTKLIB의 라이선스 전문은 RTKLIB-README.txt에 보관한다.

native/test_pvt.c는 원본 계산 함수로 합성 관측값을 생성하고 알려진 위치와 독립 컨텍스트 결과를 비교한다.
실제 GNSS 장비나 외부 DTN 프로토콜 시험을 대신하지 않는다.
Java NativePvtIntegrationTest는 기존/확장 DLL의 AFS 출력과 수신 복원 결과를 검증한다.

UBX-RXM-SFRBX v2는 신호 식별자 위치가 reserved0인 펌웨어가 있으므로,
Java 입력 어댑터에서 LNAV preamble 위치까지 확인하여 CNAV가 계산에 섞이지 않게 한다.
기존 GRAW 저장 형식과 원본 RTKLIB는 변경하지 않는다.
메시지 배열 참고: https://content.u-blox.com/sites/default/files/ZED-F9T-10B_IntegrationManual_UBX-20033630.pdf (3.12.1.2)
검토한 원본 파일의 고정 해시 목록은 UPSTREAM-SHA256.txt에 보관한다.
