#include "lnis_afs_codec.h"
#include <stdio.h>
#include <string.h>

/* 동일한 시험 입력의 출력 전문을 남겨 Windows DLL과 Linux SO를 비트 단위로 비교한다. */
int main(void)
{
    uint8_t sb2[1176], sb3[846], sb4[846], frame[750];
    uint8_t restored2[1176], restored3[846], restored4[846];
    lnis_afs_decode_status status;
    unsigned int seed = 123456789u;
    int sample, i;
    for (sample = 0; sample < 4; sample++) {
        for (i = 0; i < 1176; i++) {
            seed = seed * 1664525u + 1013904223u;
            sb2[i] = (uint8_t)(seed >> 31);
        }
        for (i = 0; i < 846; i++) {
            seed = seed * 1664525u + 1013904223u;
            sb3[i] = (uint8_t)(seed >> 31);
            seed = seed * 1664525u + 1013904223u;
            sb4[i] = (uint8_t)(seed >> 31);
        }
        uint8_t toi = (uint8_t)(sample * 33);
        if (lnis_afs_encode_frame(toi, sb2, 1176, sb3, 846, sb4, 846, frame, 750) != 0) return 1;
        if (lnis_afs_decode_frame(toi, frame, 750, restored2, 1176, restored3, 846,
                restored4, 846, &status) != 0) return 2;
        if (!status.sb2_ok || !status.sb3_ok || !status.sb4_ok) return 3;
        if (memcmp(sb2, restored2, 1176) || memcmp(sb3, restored3, 846) ||
                memcmp(sb4, restored4, 846)) return 4;
        printf("AFS[%d]=", toi);
        for (i = 0; i < 750; i++) printf("%02x", frame[i]);
        printf("\n");
    }
    return 0;
}
