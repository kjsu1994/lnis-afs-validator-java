## 전체 흐름
> - RAWX에서 Observation Epoch, Pseudorange, Doppler, C/N0, Tracking Status를 얻는다.
> - SFRBX는 현재 GRAW NavigationUpdate로 수집되지만, SB2 Ephemeris 생성에는 직접 사용하지 않는다.
> - 현재 SB2는 Sb2PayloadCodec의 LANS-AFS-SIM default_almanac 기반 PRN 1~8 고정 Ephemeris Profile과 GPS Week/AFS ITOW로 생성한다.
> - GRAW 조각은 SB3/SB4에 배치한다.

향후 SFRBX → Ephemeris Decode → SB2 기능을 구현하면 실제 GNSS Navigation 기반 구조로 확장할 수 있다
```text
┌────────────────────────────────────┐
│ 송신부 프로그램                      │
│                                    │
│ u-blox                             │
│  ├─ RAWX                           │
│  │   ├─ Observation Epoch          │
│  │   │    ├─ GPS Week              │
│  │   │    └─ Receiver TOW          │
│  │   │                             │
│  │   ├─ Pseudorange                │
│  │   ├─ Doppler                    │
│  │   ├─ C/N0                       │
│  │   └─ Tracking Status            │
│  │                                 │
│  └─ SFRBX                          │
│       │                            │
│       ▼                            │
│ Navigation Decoder(RTKLIB 오픈소스) │
│       │                            │
│       ▼                            │
│   Ephemeris 추출                    │
│     ├─ toe                         │
│     ├─ A / sqrtA                   │
│     ├─ e                           │
│     ├─ i0                          │
│     ├─ Ω0                          │
│     ├─ ω                           │
│     ├─ M0                          │
│     ├─ Δn                          │
│     ├─ Ωdot                        │
│     ├─ IDOT                        │
│     ├─ Cuc/Cus                     │
│     ├─ Crc/Crs                     │
│     ├─ Cic/Cis                     │
│     ├─ toc                         │
│     ├─ af0                         │
│     └─ af1                         │
│       │                            │
│       ▼                            │
│   SB2 Payload 생성                  │
│       │                            │
│       ▼                            │
│   AFS Frame 생성                    │
│       ├─ SB2 = Ephemeris + 시간정보  │
│       ├─ SB3 = 필요 데이터           │
│       └─ SB4 = 필요 데이터           │
└────────────────┬───────────────────┘
                 │
                 │
                 │ HTTP POST (REST API)
                 ▼
┌────────────────────────────────────┐
│ DTN / HDTN REST Adapter            │
│                                    │
│ BPv7 전송 설정                      │
│ ├─ sourceEid                       │
│ ├─ destinationEid                  │
│ ├─ lifetime                        │
│ └─ status report 설정               │
│                                    │
│ Application Payload                │
│ ├─ Observation Epoch               │
│ └─ Satellites[]                    │
│      ├─ PRN / SV ID                │
│      ├─ AFS Frame                  │
│      ├─ Pseudorange                │
│      ├─ Doppler                    │
│      ├─ C/N0                       │
│      └─ Tracking Status            │
│                                    │
│              ↓                     │
│         BPv7 Bundle 생성            │
└────────────────┬───────────────────┘
                 │
                 ▼
             HDTN / BPv7
                 │
           Store & Forward
                 │
                 ▼
          HDTN Receiver
                 │
                 ▼
┌────────────────────────────────────┐
│ 수신 DTN REST Adapter               │
│                                    │
│ BPv7 Bundle 수신                    │
│        ↓                           │
│ Payload Block 추출                  │
│        ↓                           │
│ Application Payload 복원            │
└────────────────┬───────────────────┘
                 │
                 │ HTTP 응답 / Callback
                 ▼
┌────────────────────────────────────┐
│ 수신부 프로그램                      │
│                                    │
│ Application Payload                │
│ ├─ Observation Epoch               │
│ └─ Satellites[]                    │
│      ├─ PRN / SV ID                │
│      ├─ AFS Frame                  │
│      ├─ Pseudorange                │
│      ├─ Doppler                    │
│      ├─ C/N0                       │
│      └─ Tracking Status            │
│                                    │
│      AFS Frame Decode              │
│             ↓                      │
│            SB2                     │
│             ↓                      │
│      Ephemeris 복원                 │
│             │                      │
│             ├──────────────┐       │
│             │              │       │
│             ▼              ▼       │
│      Satellite Position  Clock     │
│      / Velocity 계산      보정      │
│             │              │       │
│             └──────┬───────┘       │
│                    │               │
│       Pseudorange / Doppler        │
│       Observation Epoch            │
│       PRN / SV ID                  │
│                    │               │
│                    ▼               │
│                PVT Solver          │
│                    │               │
│                    ▼               │
│           Position / Velocity      │
│           Receiver Clock Bias      │
└────────────────────────────────────┘
```

## Rest API로 DTN 번들구성을 위해 BPv7 구성 body
```text
{
// ==============================
// 애플리케이션 식별
// ==============================
"protocolVersion": 1,

"messageId": "DTN-20260902-000015",

"sessionId": "AFS-DTN-TEST-20260902-001",

"sequence": 15,

// ==============================
// GNSS Observation Epoch
// ==============================
"epoch": {
"gpsWeek": 2434,

    "receiverTowSeconds": 217245.000
},

// ==============================
// 위성별 데이터
// ==============================
"satellites": [
{
"gnssId": 0,

      "svId": 3,

      "prn": 3,

      "signalId": 0,

      // AFS 전체 Frame
      "afsFrame": {
        "frameIndex": 15,

        "frameDataBase64": "zGP3RTb0ngSgAA...",

        "frameCrc32": "8A91D42C"
      },

      // RF Tracking을 생략하기 때문에 별도로 전달
      "observation": {
        "pseudorangeMeters": 21435821.27,

        "dopplerHz": -2345.72,

        "cn0DbHz": 43.0,

        "trackingStatus": 15
      }
    }
],

// ==============================
// 무결성/시험 검증
// ==============================
"integrity": {
    "afsFramesSha256": "b31d...91af",

    "satelliteCount": 1,

    "afsFrameCount": 1
}
}
```

##  BPv7 전체 예시(Primary Block 전체) - Json
```json
{
  "bpv7Bundle": {

    // =====================================
    // PRIMARY BLOCK
    // =====================================
    "primaryBlock": {

      "version": 7,

      "bundleProcessingControlFlags": {
        "isFragment": false,
        "administrativeRecord": false,
        "mustNotFragment": true,

        "requestApplicationAcknowledgement": false,

        "requestStatusTime": true,

        "requestReceptionReport": true,
        "requestForwardingReport": true,
        "requestDeliveryReport": true,
        "requestDeletionReport": false
      },

      "crcType": 2,

      "destination": "ipn:20.1",

      "source": "ipn:10.1",

      "reportTo": "ipn:10.2",

      "creationTimestamp": {
        "dtnTimeMs": 841000215123,
        "sequenceNumber": 0
      },

      "lifetimeMs": 60000,

      "crc": "..."
    },


    // =====================================
    // EXTENSION BLOCK - OPTIONAL
    // =====================================
    "extensionBlocks": [

      {
        "blockType": 10,
        "blockNumber": 2,

        "blockProcessingControlFlags": 0,

        "crcType": 2,

        "hopLimit": 16,

        "hopCount": 0,

        "crc": "..."
      },

      {
        "blockType": 7,
        "blockNumber": 3,

        "blockProcessingControlFlags": 0,

        "crcType": 2,

        "bundleAgeMs": 0,

        "crc": "..."
      }
    ],


    // =====================================
    // PAYLOAD BLOCK
    // =====================================
    "payloadBlock": {

      "blockType": 1,

      "blockNumber": 1,

      "blockProcessingControlFlags": 0,

      "crcType": 2,

      "payload": {

        "protocolVersion": 1,

        "messageId": "DTN-20260902-000015",

        "sessionId": "AFS-DTN-TEST-20260902-001",

        "sequence": 15,

        "epoch": {
          "gpsWeek": 2434,

          "receiverTowSeconds": 217245.000
        },

        "satellites": [
          {
            "gnssId": 0,

            "svId": 3,

            "prn": 3,

            "signalId": 0,

            "afsFrame": {
              "frameIndex": 15,

              "frameDataBase64":
                "zGP3RTb0ngSgAA...",

              "frameCrc32":
                "8A91D42C"
            },

            "observation": {
              "pseudorangeMeters":
                21435821.27,

              "dopplerHz":
                -2345.72,

              "cn0DbHz":
                43.0,

              "trackingStatus":
                15
            }
          }
        ]
      },

      "crc": "..."
    }
  }
}
```

##  BPv7 전체 예시(Primary Block 전체) - CBOR
- bundle 구조
```text
┌─────────────────────────────────────────┐
│ CBOR Bundle                             │
│                                         │
│ 0x9F                                    │
│   ↓                                     │
│ ┌─────────────────────────────────────┐ │
│ │ Primary Block                       │ │
│ │                                     │ │
│ │ version = 7                         │ │
│ │ flags                               │ │
│ │ destination = ipn:20.1              │ │
│ │ source      = ipn:10.1              │ │
│ │ report-to   = ipn:10.2              │ │
│ │ creation timestamp                  │ │
│ │ lifetime                            │ │
│ │ CRC                                 │ │
│ └─────────────────────────────────────┘ │
│                                         │
│ ┌─────────────────────────────────────┐ │
│ │ Extension Blocks (선택)              │ │
│ │ Hop Count / Bundle Age ...          │ │
│ └─────────────────────────────────────┘ │
│                                         │
│ ┌─────────────────────────────────────┐ │
│ │ Payload Block                       │ │
│ │                                     │ │
│ │ type = 1                            │ │
│ │ number = 1                          │ │
│ │                                     │ │
│ │ bstr {                              │ │
│ │   protocolVersion                   │ │
│ │   sessionId                         │ │
│ │   sequence                          │ │
│ │   epoch                             │ │
│ │   satellites[]                      │ │
│ │     ├ PRN                           │ │
│ │     ├ AFS Frame 750 byte            │ │
│ │     ├ Pseudorange                   │ │
│ │     └ Doppler                       │ │
│ │ }                                   │ │
│ │                                     │ │
│ │ CRC                                 │ │
│ └─────────────────────────────────────┘ │
│                                         │
│ 0xFF                                    │
└─────────────────────────────────────────┘
``` 
```text
BPv7 Bundle
│
├── Primary Block
│    │
│    ├── 7
│    ├── Flags
│    ├── CRC Type
│    ├── Destination = ipn:20.1
│    ├── Source      = ipn:10.1
│    ├── Report-To   = ipn:10.2
│    ├── Creation Timestamp
│    ├── Lifetime
│    └── CRC
│
├── Hop Count Block
│    └── [16, 0]
│
├── Bundle Age Block
│    └── 0 ms
│
└── Payload Block
     │
     ├── Type = 1
     ├── Number = 1
     ├── Flags
     ├── CRC Type
     │
     └── byte string
          │
          └── 우리 CBOR Payload
               │
               ├── protocolVersion
               ├── messageId
               ├── sessionId
               ├── sequence
               │
               ├── epoch
               │    ├── GPS Week
               │    └── Receiver TOW
               │
               └── satellites[]
                    │
                    ├── PRN/SV
                    │
                    ├── AFS Frame 750 byte
                    │
                    └── Observation
                         ├── Pseudorange
                         ├── Doppler
                         ├── C/N0
                         └── Tracking Status
```
- BPv7 구조에 맞춘 CBOR Diagnostic Notation
```text
[_

/ ========================================================= /
/ 1. PRIMARY BLOCK                                          /
/ ========================================================= /

[
7,
/ version = 7 /


    213060,
    / Bundle Processing Control Flags
       bit 2  = mustNotFragment
       bit 6  = requestStatusTime
       bit 14 = requestReceptionReport
       bit 16 = requestForwardingReport
       bit 17 = requestDeliveryReport

       decimal = 213060
       hex     = 0x00034044
    /


    2,
    / CRC Type
       0 = No CRC
       1 = CRC16/X-25
       2 = CRC32C
    /


    [
      2,
      [20, 1]
    ],
    / Destination EID
       scheme code 2 = ipn
       node    = 20
       service = 1

       즉 ipn:20.1
    /


    [
      2,
      [10, 1]
    ],
    / Source EID
       ipn:10.1
    /


    [
      2,
      [10, 2]
    ],
    / Report-To EID
       ipn:10.2
    /


    [
      841000215123,
      0
    ],
    / Creation Timestamp
       [DTN Time, Sequence Number]

       DTN Time은
       2000-01-01 00:00:00 UTC 이후 경과 milliseconds

       sequence = 같은 DTN time에서 Bundle 식별용
    /


    60000,
    / Lifetime
       60000 ms = 60초
    /


    h'11223344'
    / Primary Block CRC32C
       실제 값은 Primary Block을 encode한 뒤 계산됨.
       여기서는 설명용 예시 값.
    /
],



/ ========================================================= /
/ 2. HOP COUNT EXTENSION BLOCK                              /
/ ========================================================= /

[
10,
/ Block Type Code
10 = Hop Count
/


    2,
    / Block Number = 2 /


    0,
    / Block Processing Control Flags /


    2,
    / CRC Type = CRC32C /


    h'821000',
    / Block-Type-Specific Data

       이 byte string 내부의 CBOR:
       [
         16,   / hopLimit /
         0     / hopCount /
       ]

       Diagnostic 형태로 보면:
       [16, 0]

       CBOR hex:
       82 10 00
    /


    h'22334455'
    / Extension Block CRC32C
       설명용 값
    /
],



/ ========================================================= /
/ 3. BUNDLE AGE EXTENSION BLOCK                             /
/ ========================================================= /

[
7,
/ Block Type Code
7 = Bundle Age
/


    3,
    / Block Number = 3 /


    0,
    / Block Processing Flags /


    2,
    / CRC32C /


    h'00',
    / Block-Type-Specific Data

       Bundle Age = 0 ms

       내부 CBOR:
       0
    /


    h'33445566'
    / CRC32C
       설명용 값
    /
],



/ ========================================================= /
/ 4. PAYLOAD BLOCK                                          /
/ ========================================================= /

[
1,
/ Block Type Code
1 = Payload
/


    1,
    / Payload Block Number
       Payload는 항상 block number = 1
    /


    0,
    / Block Processing Control Flags /


    2,
    / CRC Type = CRC32C /



    h'
      A6

      6F
      70726F746F636F6C56657273696F6E
      01

      69
      6D6573736167654964
      74
      44544E2D32303236303930322D303030303135

      69
      73657373696F6E4964
      78 1C
      4146532D44544E2D544553542D32303236303930322D303031

      68
      73657175656E6365
      0F

      65
      65706F6368
      A2

        67
        6770735765656B
        19 0982

        72
        7265636569766572546F775365636F6E6473
        FB 410A843400000000


      6A
      736174656C6C69746573
      81

        A6

          66
          676E73734964
          00

          64
          73764964
          03

          63
          70726E
          03

          68
          7369676E616C4964
          00


          68
          6166734672616D65
          A3

            6A
            6672616D65496E646578
            0F

            69
            6672616D6544617461

            59 02EE
            CC63F74536F49E04A0
            ...
            ... 실제 AFS Frame 750 byte ...
            ...
            
            6A
            6672616D654372633332

            44
            8A91D42C


          6B
          6F62736572766174696F6E
          A4

            71
            70736575646F72616E67654D6574657273
            FB 417472DED451EB85

            69
            646F70706C6572487A
            FB C0A25370A3D70A3D

            67
            636E304462487A
            FB 4045800000000000

            6E
            747261636B696E67537461747573
            0F
    ',
    / ↑ Payload Block의 block-type-specific data

       중요한 점:
       이것은 "우리 애플리케이션 Payload를 CBOR로 직렬화한 byte[]"

       안쪽을 사람이 읽기 쉽게 풀면 아래 구조임:

       {
         "protocolVersion": 1,

         "messageId":
           "DTN-20260902-000015",

         "sessionId":
           "AFS-DTN-TEST-20260902-001",

         "sequence": 15,

         "epoch": {
           "gpsWeek": 2434,
           "receiverTowSeconds": 217245.0
         },

         "satellites": [
           {
             "gnssId": 0,
             "svId": 3,
             "prn": 3,
             "signalId": 0,

             "afsFrame": {
               "frameIndex": 15,

               "frameData":
                 h'CC63F74536F49E04A0...',

               "frameCrc32":
                 h'8A91D42C'
             },

             "observation": {
               "pseudorangeMeters": 21435821.27,
               "dopplerHz": -2345.72,
               "cn0DbHz": 43.0,
               "trackingStatus": 15
             }
           }
         ]
       }
    /


    h'44556677'
    / Payload Block CRC32C
       설명용 값
    /
]

]
```

## 고려할 점
>u-blox에서 얻는 GNSS 데이터는 지구 중심 GPS 궤도/좌표계 기준이고, 현재 PocketSDR-AFS/LANS-AFS-SIM의 PVT 계산은 달 중심 AFS 시뮬레이션 기준이라서, 둘을 그대로 섞으면 계산 기준이 서로 달라 PVT 결과가 물리적으로 의미가 없음

| 항목           | u-blox GNSS     | LANS/PocketSDR-AFS |
| ------------ | --------------- | ------------------ |
| 기준 천체        | 지구              | 달                  |
| 중력 상수        | Earth GM        | Moon GM            |
| 기준 반경        | 지구 반경           | 달 반경               |
| 위성 궤도        | GPS 등 지구 궤도     | 달 주변 AFS 가상 궤도     |
| 좌표계          | Earth-centered  | Moon-centered 성격   |
| Ephemeris 의미 | GPS 위성 궤도       | AFS 달 궤도           |
| Pseudorange  | 지구 GNSS 위성까지 거리 | 달 AFS 위성까지 거리      |
| PVT 결과       | 지구상의 수신기 위치     | 달 기준 수신기 위치        |

- 오픈소스에서 달의 중력상수와 달의 반경 사용하고 있음
```c
#define GM_MOON 4.9028e12
#define R_MOON 1737.4e3
```

- Pocket SDR의 PVT가 아닌 지구 GNSS용 PVT Solver 사용
> [ tomojitakasu/RTKLIB ]
> https://github.com/tomojitakasu/RTKLIB.git

src/pntpos.c 
```c
//실제 Single Point PVT 계산
extern int pntpos(
    const obsd_t *obs,
    int n,
    const nav_t *nav,
    const prcopt_t *opt,
    sol_t *sol,
    double *azel,
    ssat_t *ssat,
    char *msg)
{
    ...

    /* satellite positions, velocities and clocks */
    satposs(
        sol->time,
        obs,
        n,
        nav,
        opt_.sateph,
        rs,
        dts,
        var,
        svh
    );

    /* estimate receiver position with pseudorange */
    stat = estpos(
        obs,
        n,
        rs,
        dts,
        var,
        svh,
        nav,
        &opt_,
        sol,
        azel_,
        vsat,
        resp,
        msg
    );

    /* estimate receiver velocity with doppler */
    if (stat) {
        estvel(
            obs,
            n,
            rs,
            dts,
            nav,
            &opt_,
            sol,
            azel_,
            vsat
        );
    }

    ...

    return stat;
}
```
ephemeris.c
```c
/* broadcast ephemeris to satellite position and clock bias */
extern void eph2pos(
        gtime_t time,
    const eph_t *eph,
        double *rs,
        double *dts,
        double *var)
{
    double tk,M,E,Ek,sinE,cosE;
    double u,r,i,O;
    double sin2u,cos2u;
    double x,y,sinO,cosO,cosi;
    double mu,omge;

    int n,sys,prn;

    /* Ephemeris 기준 시각 toe와 현재 시각 차이 */
    tk = timediff(time, eph->toe);

    /* GNSS 종류에 따라 지구 중력상수 선택 */
    switch ((sys = satsys(eph->sat, &prn))) {
    case SYS_GAL:
        mu   = MU_GAL;
        omge = OMGE_GAL;
        break;

    case SYS_CMP:
        mu   = MU_CMP;
        omge = OMGE_CMP;
        break;

    default:
        mu   = MU_GPS;
        omge = OMGE;
        break;
}

    /* Mean Anomaly 계산 */
    M = eph->M0 +
            (sqrt(mu /
                    (eph->A * eph->A * eph->A))
                    + eph->deln) * tk;

    /* Kepler Equation 풀이 */
    for (n=0,E=M,Ek=0.0;
         fabs(E-Ek)>RTOL_KEPLER &&
                 n<MAX_ITER_KEPLER;
         n++)
    {
        Ek = E;

        E -= (E
                - eph->e * sin(E)
                - M)
                /
                (1.0
                        - eph->e * cos(E));
    }

    sinE = sin(E);
    cosE = cos(E);

    /* 궤도면에서 위치 계산 */
    u = atan2(
            sqrt(1.0-eph->e*eph->e) * sinE,
            cosE-eph->e)
            + eph->omg;

    r = eph->A *
            (1.0 - eph->e*cosE);

    i = eph->i0 +
            eph->idot * tk;

    /* harmonic correction */
    sin2u = sin(2.0*u);
    cos2u = cos(2.0*u);

    u += eph->cus*sin2u
            + eph->cuc*cos2u;

    r += eph->crs*sin2u
            + eph->crc*cos2u;

    i += eph->cis*sin2u
            + eph->cic*cos2u;

    x = r*cos(u);
    y = r*sin(u);

    cosi = cos(i);

    /* ECEF 좌표로 변환 */
    O = eph->OMG0
            + (eph->OMGd - omge) * tk
            - omge * eph->toes;

    sinO = sin(O);
    cosO = cos(O);

    rs[0] = x*cosO
            - y*cosi*sinO;

    rs[1] = x*sinO
            + y*cosi*cosO;

    rs[2] = y*sin(i);

    /* 위성 Clock Bias */
    tk = timediff(time, eph->toc);

    *dts =
        eph->f0
                + eph->f1 * tk
                + eph->f2 * tk * tk;

    /* 상대론 보정 */
    *dts -=
        2.0 *
                sqrt(mu * eph->A) *
                eph->e *
                        sinE /
                        SQR(CLIGHT);

    /* 위성 위치/Clock 오차 분산 */
    *var = var_uraeph(eph->sva);
}
```

### 변경 후 처리과정
> - RAWX에서 Observation Epoch, Pseudorange, Doppler, C/N0, Tracking Status를 수집한다.
> - SFRBX에서 GNSS Navigation Raw Words를 수집한다.
> - RTKLIB Navigation Decoder를 이용해 SFRBX에서 Full Broadcast Ephemeris를 복원한다.
> - Full Ephemeris 중 AFS SB2에서 정의한 필드는 SB2 Payload로 Mapping한다.
> - 현재 구현에서는 GRAW Fragment를 SB3/SB4에 배치한다.
> - RTKLIB PVT에는 AFS SB2에 없는 Ephemeris 필드도 필요하므로 Full Ephemeris를 별도로 보존/전달한다.
> - 수신측에서는 RAWX Observation과 Full Ephemeris를 RTKLIB obsd_t, nav_t/eph_t로 변환한 뒤 pntpos()를 호출한다.
> -RTKLIB은 satposs() → estpos() → estvel() 순서로 위성 상태, 수신기 위치, 수신기 속도를 계산한다.
```text
┌─────────────────────────────────────────────┐
│                 u-blox                     │
├─────────────────────────────────────────────┤
│                                             │
│ RAWX                         SFRBX           │
│   │                            │             │
│   ▼                            ▼             │
│ Observation              Navigation Words   │
│ ├ Pseudorange                  │             │
│ ├ Doppler                      ▼             │
│ ├ Epoch                RTKLIB Navigation    │
│ ├ C/N0                    Decoder           │
│ └ Status                       │             │
│                                ▼             │
│                       Full GPS Ephemeris     │
│                                │             │
│                         ┌──────┴───────┐     │
│                         │              │     │
│                         ▼              ▼     │
│                   AFS SB2 Mapping   PVT용    │
│                         │         Full Eph.  │
│                         ▼              │     │
│                        SB2             │     │
│                         │              │     │
│                         ▼              │     │
│                    AFS Frame           │     │
│                         │              │     │
│                         └──────┬───────┘     │
│                                │             │
└────────────────────────────────┼─────────────┘
                                 ▼

                      Application Payload
                      ├─ Epoch
                      └─ Satellites[]
                           ├─ PRN
                           ├─ AFS Frame
                           ├─ Full Ephemeris
                           ├─ Pseudorange
                           ├─ Doppler
                           ├─ C/N0
                           └─ Tracking Status

                                 ↓
                              REST
                                 ↓

                          HDTN / BPv7
                         Store & Forward

                                 ↓

                         Receiver Adapter
                                 ↓

                       Application Payload
                                 ↓
                 ┌───────────────┴────────────────┐
                 │                                │
                 ▼                                ▼

           AFS Frame                      Full Ephemeris
               ↓                               +
          AFS Decode                      Observation
               ↓                               │
              SB2                              │
               ↓                               │
        AFS 데이터 검증                        │
                                                ▼
                                      RTKLIB Adapter
                                      ★ 직접 구현
                                                │
                             ┌──────────────────┴────────────┐
                             │                               │
                             ▼                               ▼
                       Observation DTO                 Ephemeris DTO
                             ↓                               ↓
                          obsd_t[]                       eph_t / nav_t
                             │                               │
                             └──────────────┬────────────────┘
                                            ▼

                                         RTKLIB
                                            │
                                            ▼
                                         satposs()
                                            │
                         ┌──────────────────┴────────────────┐
                         │                                   │
                         ▼                                   ▼
                 Satellite Position                    Satellite Clock
                 Satellite Velocity                    Bias / Drift
                         │                                   │
                         └──────────────────┬────────────────┘
                                            ▼
                                          estpos()
                                            │
                                    Pseudorange 사용
                                            ↓
                                    Receiver X/Y/Z
                                    Receiver Clock Bias
                                            │
                                            ▼
                                          estvel()
                                            │
                                      Doppler 사용
                                            ↓
                                   Receiver VX/VY/VZ
                                            │
                                            ▼
                                           PVT
```