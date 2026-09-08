#include "lnis_pvt.h"
#include "rtklib.h"

/* 직접 작성한 연결 코드다. 항법 해석과 위치/속도 알고리즘은 원본 RTKLIB를 호출한다.
 * 컨텍스트는 시험마다 새로 생성하며 다른 시험의 항법 캐시를 공유하지 않는다. */
typedef struct {
    nav_t nav;
    uint8_t gps_frames[32][150];
} lnis_pvt_context;

uint32_t lnis_pvt_get_abi_version(void) { return 1; }

void *lnis_pvt_create(void) {
    lnis_pvt_context *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return NULL;
    ctx->nav.eph = calloc(MAXSAT, sizeof(eph_t));
    if (!ctx->nav.eph) { free(ctx); return NULL; }
    ctx->nav.n = ctx->nav.nmax = MAXSAT;
    return ctx;
}

void lnis_pvt_destroy(void *context) {
    lnis_pvt_context *ctx = context;
    if (!ctx) return;
    free(ctx->nav.eph);
    free(ctx);
}

int32_t lnis_pvt_gps_navigation(void *context, int32_t prn,
    int32_t observation_week, const uint32_t *words, uint32_t count) {
    lnis_pvt_context *ctx = context;
    uint8_t block[30];
    int i, id, sat, decoded_week, shift;
    eph_t eph = {0};
    if (!ctx || !words || count != 10 || prn < 1 || prn > 32 ||
        observation_week < 0 || observation_week > 8191) return -1;
    for (i = 0; i < 10; i++) {
        if (words[i] > 0xffffffu) return -1;
        block[i*3] = (uint8_t)(words[i] >> 16);
        block[i*3+1] = (uint8_t)(words[i] >> 8);
        block[i*3+2] = (uint8_t)words[i];
    }
    if (block[0] != 0x8b) return -1;
    id = (block[5] >> 2) & 7;
    if (id < 1 || id > 5) return -1;
    memcpy(ctx->gps_frames[prn-1] + (id-1)*30, block, 30);
    if (id == 4) decode_frame(ctx->gps_frames[prn-1], NULL, NULL, ctx->nav.ion_gps, NULL);
    if (id > 3 || !decode_frame(ctx->gps_frames[prn-1], &eph, NULL, NULL, NULL)) return 0;
    /* 原本 adjgpsweek()의 현재 날짜 의존성을 관측 주차에 맞춰 보정한다.
     * 입력 과거 데이터를 DTN 수신 시점과 무관하게 재현하기 위한 어댑터 처리다. */
    time2gpst(eph.ttr, &decoded_week);
    shift = (int)floor((observation_week - decoded_week + 512.0) / 1024.0) * 1024;
    eph.week += shift;
    eph.toe = timeadd(eph.toe, shift * 604800.0);
    eph.toc = timeadd(eph.toc, shift * 604800.0);
    eph.ttr = timeadd(eph.ttr, shift * 604800.0);
    sat = satno(SYS_GPS, prn);
    eph.sat = sat;
    ctx->nav.eph[sat-1] = eph;
    return 1;
}

int32_t lnis_pvt_gps_solve(void *context, int32_t week, double tow,
    const double *observations, uint32_t count, double *result,
    char *message, uint32_t message_size) {
    lnis_pvt_context *ctx = context;
    obsd_t obs[32] = {{0}};
    sol_t sol = {0};
    prcopt_t opt = prcopt_default;
    char error[128] = {0};
    uint32_t i;
    int ok;
    if (!ctx || !observations || !result || !message || message_size < 128 ||
        count < 1 || count > 32 || week < 0 || week > 8191 ||
        !isfinite(tow) || tow < 0 || tow >= 604800) return -1;
    memset(result, 0, 9*sizeof(double));
    message[0] = 0;
    for (i = 0; i < count; i++) {
        double prn = observations[i*4];
        double range = observations[i*4+1], doppler = observations[i*4+2];
        double cno = observations[i*4+3];
        uint32_t j;
        if (!isfinite(prn) || prn < 1 || prn > 32 || prn != floor(prn) ||
            !isfinite(range) || range <= 0 || !isfinite(doppler) ||
            !isfinite(cno) || cno < 0 || cno > 100) return -1;
        for (j = 0; j < i; j++) if (observations[j*4] == prn) return -1;
        obs[i].time = gpst2time(week, tow);
        obs[i].sat = (uint8_t)satno(SYS_GPS, (int)prn);
        obs[i].rcv = 1;
        obs[i].code[0] = CODE_L1C;
        obs[i].P[0] = range;
        obs[i].D[0] = (float)doppler;
        obs[i].SNR[0] = (uint16_t)(cno / SNR_UNIT);
    }
    /* PocketSDR sdr_pvt.c/update_sol()와 같은 단독 측위 보정 설정.
     * 위성군은 이 GPS 전용 어댑터에 한정한다. */
    opt.navsys = SYS_GPS;
    opt.err[1] = opt.err[2] = 0.03;
    opt.ionoopt = IONOOPT_BRDC;
    opt.tropopt = TROPOPT_SAAS;
    opt.elmin = 15.0 * D2R;
    ok = pntpos(obs, (int)count, &ctx->nav, &opt, &sol, NULL, NULL, error);
    snprintf(message, message_size, "%s", error);
    if (!ok) return 0;
    for (i = 0; i < 6; i++) result[i] = sol.rr[i];
    result[6] = sol.dtr[0];
    result[7] = sol.ns;
    result[8] = sol.qv[0] > 0 && sol.qv[1] > 0 && sol.qv[2] > 0;
    return 1;
}
