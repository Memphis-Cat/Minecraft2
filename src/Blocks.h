#pragma once
#include "Assets.h"

namespace mc {
using BlockId = uint16_t;
enum class Face : uint8_t { West=0, East=1, Down=2, Up=3, North=4, South=5 };
enum class TintMode : uint8_t { None=0, Grass=1 };

struct BlockDefinition {
    BlockId id{};
    std::string name;
    float hardness{1.0f};
    int breakTicks{30};
    bool solid{true};
    bool breakable{true};
    std::array<std::string,6> textureNames{};
    std::array<uint16_t,6> textureSlices{};
    std::array<TintMode,6> tint{};
};

class BlockRegistry {
public:
    void Discover(const std::filesystem::path& assetRoot, WarningLog& warnings);
    void ResolveTextures(const TexturePack& pack);
    const BlockDefinition& Get(BlockId id) const;
    BlockId Find(std::string_view name) const;
    const std::vector<BlockDefinition>& All() const { return blocks_; }
    std::vector<std::string> RequiredTextureNames() const;
private:
    std::vector<BlockDefinition> blocks_;
    std::unordered_map<std::string,BlockId> byName_;
};
}
