#include "halalify_site_filter.h"

#include <memory>
#include <string>

#include "core/site_filter.h"

struct hb_site_filter {
    halalify::SiteFilterEngine engine;
    std::string error;
};

extern "C" {

hb_site_filter* hb_site_filter_create_from_buffer(
        const uint8_t* data,
        size_t size,
        const char** error_message) {
    static thread_local std::string last_error;
    last_error.clear();
    std::unique_ptr<hb_site_filter> filter(new hb_site_filter{});
    if (!filter->engine.Load(data, size, &filter->error)) {
        last_error = filter->error;
        if (error_message != nullptr) *error_message = last_error.c_str();
        return nullptr;
    }
    if (error_message != nullptr) *error_message = nullptr;
    return filter.release();
}

int hb_site_filter_is_blocked(const hb_site_filter* filter, const char* domain) {
    if (filter == nullptr || domain == nullptr) return 0;
    return filter->engine.IsBlocked(domain) ? 1 : 0;
}

void hb_site_filter_destroy(hb_site_filter* filter) {
    delete filter;
}

}  // extern "C"
