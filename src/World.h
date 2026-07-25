#pragma once
#include "Blocks.h"
#include <memory>

namespace mc {
struct VoxelVertex {
    float x{},y{},z{};
    float u{},v{};
    uint32_t texture{};
    uint32_t color{0xFFFFFFFFu};
};
struct MeshData {std::vector<VoxelVertex> vertices;std::vector<uint32_t> indices;uint64_t revision{};};
struct RayHit {Int3 block;Int3 previous;Int3 normal;BlockId id{};double distance{};};

class Chunk {
public:
    explicit Chunk(ChunkCoord coord):coord_(coord),blocks_(kChunkSize*kWorldHeight*kChunkSize,0){}
    ChunkCoord Coord() const{return coord_;}
    BlockId GetLocal(int x,int y,int z) const;
    void SetLocal(int x,int y,int z,BlockId id);
    bool Dirty() const{return dirty_;}
    void MarkDirty(){dirty_=true;}
    void ClearDirty(){dirty_=false;}
    uint64_t NextRevision(){return ++revision_;}
private:
    ChunkCoord coord_{};
    std::vector<BlockId> blocks_;
    bool dirty_{true};
    uint64_t revision_{};
};

class World {
public:
    World(const BlockRegistry& blocks,const std::filesystem::path& assetRoot,WarningLog& warnings);
    void LoadLayerProfile(const std::filesystem::path& file,WarningLog& warnings);
    void UpdateStreaming(const Vec3& playerPosition,int renderDistance=8);
    BlockId GetBlock(int x,int y,int z) const;
    bool IsSolid(int x,int y,int z) const;
    bool SetBlock(int x,int y,int z,BlockId id);
    std::optional<RayHit> Raycast(const Vec3& origin,const Vec3& direction,double maxDistance) const;
    std::vector<std::pair<ChunkCoord,MeshData>> BuildDirtyMeshes(size_t budget);
    const std::unordered_map<ChunkCoord,std::unique_ptr<Chunk>,ChunkCoordHash>& Chunks() const{return chunks_;}
    std::vector<ChunkCoord> TakeUnloaded();
private:
    struct Layer {BlockId id{};int count{};};
    void UseBuiltInLayers(WarningLog& warnings);
    void Generate(Chunk& chunk);
    MeshData BuildMesh(Chunk& chunk) const;
    void MarkDirtyAt(int wx,int wz);
    std::array<float,3> GrassTintForChunk(ChunkCoord coord) const;

    const BlockRegistry& blocks_;
    std::vector<Layer> layers_;
    std::vector<uint8_t> grassColorMap_;
    uint32_t grassColorMapWidth_{},grassColorMapHeight_{};
    std::unordered_map<ChunkCoord,std::unique_ptr<Chunk>,ChunkCoordHash> chunks_;
    std::vector<ChunkCoord> unloaded_;
};
}
