#include "site_filter.h"

#include <algorithm>
#include <cctype>
#include <sstream>
#include <unordered_set>

namespace halalify {
namespace {

std::string Trim(std::string value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    const auto last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

std::string Lowercase(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    return value;
}

bool IsIpv4Address(const std::string& value) {
    return value == "0.0.0.0" || value == "127.0.0.1" || value == "::" || value == "::1";
}

std::string NormalizeRule(std::string rule) {
    rule = Trim(std::move(rule));
    if (rule.empty() || rule.front() == '#') return {};

    // Accept hosts-file lines: 0.0.0.0 example.test or 127.0.0.1 example.test.
    std::istringstream tokens(rule);
    std::string first;
    std::string second;
    tokens >> first;
    tokens >> second;
    if (IsIpv4Address(first) && !second.empty()) rule = second;
    else rule = first;

    // Also accept a small, portable subset of AdGuard syntax: ||example.test^.
    if (rule.rfind("||", 0) == 0) rule.erase(0, 2);
    if (const size_t separator = rule.find_first_of("^/$"); separator != std::string::npos) {
        rule.erase(separator);
    }
    while (!rule.empty() && (rule.front() == '.' || rule.front() == '|')) rule.erase(0, 1);
    while (!rule.empty() && rule.back() == '.') rule.pop_back();
    return Lowercase(Trim(std::move(rule)));
}

}  // namespace

bool SiteFilterEngine::Load(const uint8_t* data, size_t size, std::string* error) {
    suffixes_.clear();
    if (data == nullptr || size == 0) {
        if (error) *error = "The site blocklist file is empty.";
        return false;
    }

    std::string content(reinterpret_cast<const char*>(data), size);
    if (content.size() >= 3 &&
        static_cast<unsigned char>(content[0]) == 0xef &&
        static_cast<unsigned char>(content[1]) == 0xbb &&
        static_cast<unsigned char>(content[2]) == 0xbf) {
        content.erase(0, 3);
    }

    std::unordered_set<std::string> unique_rules;
    std::istringstream lines(content);
    std::string line;
    while (std::getline(lines, line)) {
        const std::string rule = NormalizeRule(line);
        if (!rule.empty() && rule.find('.') != std::string::npos) unique_rules.insert(rule);
    }
    suffixes_.assign(unique_rules.begin(), unique_rules.end());
    std::sort(suffixes_.begin(), suffixes_.end());
    if (suffixes_.empty()) {
        if (error) *error = "The site blocklist contains no domain rules.";
        return false;
    }
    return true;
}

bool SiteFilterEngine::IsBlocked(std::string_view domain) const {
    std::string normalized(domain);
    normalized = Lowercase(Trim(std::move(normalized)));
    while (!normalized.empty() && normalized.back() == '.') normalized.pop_back();
    if (normalized.empty()) return false;

    return std::any_of(suffixes_.begin(), suffixes_.end(), [&normalized](const std::string& suffix) {
        return normalized == suffix ||
               (normalized.size() > suffix.size() &&
                normalized.compare(normalized.size() - suffix.size(), suffix.size(), suffix) == 0 &&
                normalized[normalized.size() - suffix.size() - 1] == '.');
    });
}

}  // namespace halalify
