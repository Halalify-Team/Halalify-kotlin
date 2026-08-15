#ifndef HALALIFY_CORE_SITE_FILTER_H_
#define HALALIFY_CORE_SITE_FILTER_H_

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace halalify {

/** Cross-platform domain policy engine. Platform VPN layers only transport DNS packets. */
class SiteFilterEngine {
public:
    bool Load(const uint8_t* data, size_t size, std::string* error);
    bool IsBlocked(std::string_view domain) const;
    size_t RuleCount() const { return suffixes_.size(); }

private:
    std::vector<std::string> suffixes_;
};

}  // namespace halalify

#endif  // HALALIFY_CORE_SITE_FILTER_H_
