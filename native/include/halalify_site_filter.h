#ifndef HALALIFY_SITE_FILTER_H_
#define HALALIFY_SITE_FILTER_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct hb_site_filter hb_site_filter;

hb_site_filter* hb_site_filter_create_from_buffer(
        const uint8_t* data,
        size_t size,
        const char** error_message);

int hb_site_filter_is_blocked(
        const hb_site_filter* filter,
        const char* domain);

void hb_site_filter_destroy(hb_site_filter* filter);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // HALALIFY_SITE_FILTER_H_
