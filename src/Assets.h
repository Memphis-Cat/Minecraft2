#pragma once
#include "Common.h"
#include <map>
#include <set>

namespace mc {
class WarningLog {
public:
    void Add(std::wstring message);
    bool Empty() const { return messages_.empty(); }
    const std::vector<std::wstring>& Messages() const { return messages_; }
    std::wstring Format() const;
private:
    std::vector<std::wstring> messages_;
    std::set<std::wstring> unique_;
};

std::filesystem::path FindAssetRoot();
std::map<std::string,std::string> ReadProperties(const std::filesystem::path& path);
std::wstring Widen(std::string_view text);
std::string Narrow(std::wstring_view text);
std::string Lower(std::string value);
std::string Trim(std::string value);

class TexturePack {
public:
    void Build(const std::filesystem::path& assetRoot,
               const std::vector<std::string>& requiredNames,
               WarningLog& warnings);
    uint16_t Slice(std::string_view textureName) const;
    const std::vector<std::filesystem::path>& SlicePaths() const { return slicePaths_; }
    const std::array<uint16_t,10>& DestroyStages() const { return destroyStages_; }
private:
    std::unordered_map<std::string,uint16_t> slices_;
    std::vector<std::filesystem::path> slicePaths_;
    std::array<uint16_t,10> destroyStages_{};
};
}
