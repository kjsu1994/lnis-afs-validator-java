#ifndef LNIS_PVT_H
#define LNIS_PVT_H
#include "lnis_afs_codec.h"

/* RTKLIB의 내부 구조체를 Java에 노출하지 않는 PVT 확장 ABI다. */
LNIS_API uint32_t lnis_pvt_get_abi_version(void);
LNIS_API void *lnis_pvt_create(void);
LNIS_API void lnis_pvt_destroy(void *context);
/* GPS L1 C/A의 parity를 제외한 24-bit word 10개를 순서대로 전달한다. */
LNIS_API int32_t lnis_pvt_gps_navigation(void *context, int32_t prn,
    int32_t observation_week, const uint32_t *words, uint32_t count);
/* observations: 위성별 PRN, 의사거리(m), Doppler(Hz), C/N0(dB-Hz)의 4개 double.
 * result: ECEF 위치 3개, ECEF 속도 3개, 수신기 시계 오차(s), 사용 위성 수, 속도 유효 여부.
 * 반환 1=위치 해 성공, 0=측위 불가, -1=입력 오류. 속도 유효성은 별도 검증한다. */
LNIS_API int32_t lnis_pvt_gps_solve(void *context, int32_t week, double tow,
    const double *observations, uint32_t count, double *result,
    char *message, uint32_t message_size);
#endif
