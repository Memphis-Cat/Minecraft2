#include "World.h"
#include <Windows.h>
#include <wincodec.h>
#include <wrl/client.h>
#include <fstream>
#include <limits>
#include <queue>

using Microsoft::WRL::ComPtr;

namespace mc {
namespace {
size_t Index(int x,int y,int z){return static_cast<size_t>((y*kChunkSize+z)*kChunkSize+x);}
uint32_t PackLitColor(float r,float g,float b,uint8_t skyLight,uint8_t blockLight){
    const auto channel=[](float value){return static_cast<uint32_t>(std::clamp(value,0.0f,1.0f)*255.0f+0.5f);};
    const uint32_t packedLight=(uint32_t(skyLight&15u)<<4)|uint32_t(blockLight&15u);
    return channel(r)|(channel(g)<<8)|(channel(b)<<16)|(packedLight<<24);
}
struct MaskCell {bool valid{};bool back{};uint32_t texture{};uint32_t color{};Face face{};bool operator==(const MaskCell&)const=default;};
Face AxisFace(int axis,bool positive){if(axis==0)return positive?Face::East:Face::West;if(axis==1)return positive?Face::Up:Face::Down;return positive?Face::South:Face::North;}
float Shade(Face face){switch(face){case Face::Up:return 1.0f;case Face::Down:return 0.50f;case Face::North:case Face::South:return 0.80f;default:return 0.60f;}}
std::wstring AbsoluteForMessage(const std::filesystem::path& path){std::error_code ec;const auto absolute=std::filesystem::absolute(path,ec);return (ec?path:absolute.lexically_normal()).wstring();}

bool LoadPngRgba(const std::filesystem::path& path,std::vector<uint8_t>& pixels,uint32_t& width,uint32_t& height){
    pixels.clear();width=0;height=0;
    if(!std::filesystem::is_regular_file(path))return false;
    ComPtr<IWICImagingFactory> factory;
    HRESULT hr=CoCreateInstance(CLSID_WICImagingFactory2,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(factory.GetAddressOf()));
    if(FAILED(hr))hr=CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(factory.GetAddressOf()));
    if(FAILED(hr))return false;
    ComPtr<IWICBitmapDecoder> decoder;
    if(FAILED(factory->CreateDecoderFromFilename(path.c_str(),nullptr,GENERIC_READ,WICDecodeMetadataCacheOnLoad,decoder.GetAddressOf())))return false;
    ComPtr<IWICBitmapFrameDecode> frame;
    if(FAILED(decoder->GetFrame(0,frame.GetAddressOf())))return false;
    UINT imageWidth=0,imageHeight=0;
    if(FAILED(frame->GetSize(&imageWidth,&imageHeight))||imageWidth==0||imageHeight==0)return false;
    ComPtr<IWICFormatConverter> converter;
    if(FAILED(factory->CreateFormatConverter(converter.GetAddressOf())))return false;
    if(FAILED(converter->Initialize(frame.Get(),GUID_WICPixelFormat32bppRGBA,WICBitmapDitherTypeNone,nullptr,0.0,WICBitmapPaletteTypeCustom)))return false;
    const size_t byteCount=static_cast<size_t>(imageWidth)*imageHeight*4;
    if(byteCount>std::numeric_limits<UINT>::max())return false;
    pixels.resize(byteCount);
    if(FAILED(converter->CopyPixels(nullptr,imageWidth*4,static_cast<UINT>(byteCount),pixels.data()))){pixels.clear();return false;}
    width=imageWidth;height=imageHeight;
    return true;
}
}

World::World(const BlockRegistry& blocks,const std::filesystem::path& assetRoot,WarningLog& warnings):blocks_(blocks){
    const auto colorMapPath=assetRoot/L"textures"/L"blocks"/L"grass.png";
    if(!LoadPngRgba(colorMapPath,grassColorMap_,grassColorMapWidth_,grassColorMapHeight_)){
        if(std::filesystem::exists(colorMapPath))warnings.Add(L"Unreadable grass color map: "+AbsoluteForMessage(colorMapPath));
        else warnings.Add(L"Missing grass color map: "+AbsoluteForMessage(colorMapPath));
    }
    UseBuiltInLayers(warnings);
}

BlockId Chunk::GetLocal(int x,int y,int z) const{if(x<0||x>=kChunkSize||y<0||y>=kWorldHeight||z<0||z>=kChunkSize)return 0;return blocks_[Index(x,y,z)];}
void Chunk::SetLocal(int x,int y,int z,BlockId id){if(x<0||x>=kChunkSize||y<0||y>=kWorldHeight||z<0||z>=kChunkSize)return;blocks_[Index(x,y,z)]=id;dirty_=true;}
uint8_t Chunk::GetSkyLightLocal(int x,int y,int z) const{if(x<0||x>=kChunkSize||y<0||y>=kWorldHeight||z<0||z>=kChunkSize)return 0;return skyLight_[Index(x,y,z)];}
uint8_t Chunk::GetBlockLightLocal(int x,int y,int z) const{if(x<0||x>=kChunkSize||y<0||y>=kWorldHeight||z<0||z>=kChunkSize)return 0;return blockLight_[Index(x,y,z)];}
void Chunk::SetSkyLightLocal(int x,int y,int z,uint8_t value){if(x<0||x>=kChunkSize||y<0||y>=kWorldHeight||z<0||z>=kChunkSize)return;skyLight_[Index(x,y,z)]=std::min<uint8_t>(15,value);}
void Chunk::SetBlockLightLocal(int x,int y,int z,uint8_t value){if(x<0||x>=kChunkSize||y<0||y>=kWorldHeight||z<0||z>=kChunkSize)return;blockLight_[Index(x,y,z)]=std::min<uint8_t>(15,value);}
void Chunk::ClearLight(){std::fill(skyLight_.begin(),skyLight_.end(),0);std::fill(blockLight_.begin(),blockLight_.end(),0);}

void World::UseBuiltInLayers(WarningLog& warnings){
    layers_.clear();
    const std::array<std::pair<const char*,int>,4> defaults={{{"grass",1},{"dirt",3},{"stone",5},{"bedrock",1}}};
    for(const auto& [name,count]:defaults){const BlockId id=blocks_.Find(name);if(id!=0)layers_.push_back({id,count});else warnings.Add(L"Built-in terrain requires a missing block texture/definition: "+Widen(name));}
}

void World::LoadLayerProfile(const std::filesystem::path& file,WarningLog& warnings){
    // Optional override. Missing files intentionally produce no warning.
    if(!std::filesystem::is_regular_file(file))return;
    std::ifstream input(file,std::ios::binary);
    if(!input.is_open()){warnings.Add(L"Unreadable optional world layer profile: "+AbsoluteForMessage(file));return;}
    std::vector<Layer> parsed;
    std::string line;size_t lineNumber=0;
    while(std::getline(input,line)){
        ++lineNumber;if(lineNumber==1&&line.rfind("\xEF\xBB\xBF",0)==0)line.erase(0,3);line=Trim(line);if(line.empty()||line[0]=='#')continue;
        const auto equals=line.find('=');if(equals==std::string::npos){warnings.Add(L"Invalid world layer line "+std::to_wstring(lineNumber)+L" in "+AbsoluteForMessage(file)+L"; expected block=count");continue;}
        const std::string name=Lower(Trim(line.substr(0,equals)));int count=0;
        try{count=std::stoi(Trim(line.substr(equals+1)));}catch(...){warnings.Add(L"Invalid layer count on line "+std::to_wstring(lineNumber)+L" in "+AbsoluteForMessage(file));continue;}
        const BlockId id=blocks_.Find(name);if(id==0){warnings.Add(L"World layer profile references unknown block: "+Widen(name));continue;}if(count<=0){warnings.Add(L"Non-positive layer count for "+Widen(name)+L" in "+AbsoluteForMessage(file));continue;}parsed.push_back({id,count});
    }
    if(!parsed.empty())layers_=std::move(parsed);else warnings.Add(L"Optional world layer profile contains no valid layers; built-in terrain remains active: "+AbsoluteForMessage(file));
}

std::array<float,3> World::GrassTintForChunk(ChunkCoord coord) const{
    const double noise=std::sin(coord.x*12.9898+coord.z*78.233)*43758.5453;
    const double fraction=noise-std::floor(noise);
    double temperature=std::clamp(0.70+(fraction-0.5)*0.30,0.0,1.0);
    double humidity=std::clamp(0.75+(0.5-fraction)*0.25,0.0,1.0);
    const double adjustedHumidity=humidity*temperature;
    const int pixelX=std::clamp(static_cast<int>((1.0-temperature)*255.0),0,255);
    const int pixelY=std::clamp(static_cast<int>((1.0-adjustedHumidity)*255.0),0,255);
    if(!grassColorMap_.empty()&&grassColorMapWidth_>0&&grassColorMapHeight_>0){
        const uint32_t x=static_cast<uint32_t>((uint64_t(pixelX)*(grassColorMapWidth_-1))/255);
        const uint32_t y=static_cast<uint32_t>((uint64_t(pixelY)*(grassColorMapHeight_-1))/255);
        const size_t index=(static_cast<size_t>(y)*grassColorMapWidth_+x)*4;
        return {grassColorMap_[index]/255.0f,grassColorMap_[index+1]/255.0f,grassColorMap_[index+2]/255.0f};
    }
    const float dry=static_cast<float>(1.0-adjustedHumidity);
    return {0.28f+0.18f*dry,0.62f+0.20f*static_cast<float>(adjustedHumidity),0.16f+0.08f*dry};
}

void World::Generate(Chunk& chunk){
    int y=0;
    for(auto it=layers_.rbegin();it!=layers_.rend()&&y<kWorldHeight;++it)
        for(int n=0;n<it->count&&y<kWorldHeight;++n,++y)
            for(int z=0;z<kChunkSize;++z)for(int x=0;x<kChunkSize;++x)chunk.SetLocal(x,y,z,it->id);
    RebuildLighting(chunk);
    chunk.MarkDirty();
}

void World::RebuildLighting(Chunk& chunk){
    chunk.ClearLight();
    std::queue<Int3> open;

    // Direct skylight remains level 15 until an opaque block stops the vertical column.
    for(int z=0;z<kChunkSize;++z)for(int x=0;x<kChunkSize;++x){
        bool seesSky=true;
        for(int y=kWorldHeight-1;y>=0;--y){
            if(blocks_.Get(chunk.GetLocal(x,y,z)).solid){seesSky=false;continue;}
            if(seesSky)chunk.SetSkyLightLocal(x,y,z,15);
        }
    }

    const ChunkCoord coord=chunk.Coord();
    const auto seedBorder=[&](int x,int y,int z,int worldX,int worldZ){
        if(blocks_.Get(chunk.GetLocal(x,y,z)).solid)return;
        const uint8_t neighbor=GetSkyLight(worldX,y,worldZ);
        const uint8_t candidate=neighbor>0?uint8_t(neighbor-1):0;
        if(candidate>chunk.GetSkyLightLocal(x,y,z))chunk.SetSkyLightLocal(x,y,z,candidate);
    };
    for(int y=0;y<kWorldHeight;++y)for(int i=0;i<kChunkSize;++i){
        seedBorder(0,y,i,coord.x*kChunkSize-1,coord.z*kChunkSize+i);
        seedBorder(kChunkSize-1,y,i,(coord.x+1)*kChunkSize,coord.z*kChunkSize+i);
        seedBorder(i,y,0,coord.x*kChunkSize+i,coord.z*kChunkSize-1);
        seedBorder(i,y,kChunkSize-1,coord.x*kChunkSize+i,(coord.z+1)*kChunkSize);
    }

    for(int y=0;y<kWorldHeight;++y)for(int z=0;z<kChunkSize;++z)for(int x=0;x<kChunkSize;++x)
        if(!blocks_.Get(chunk.GetLocal(x,y,z)).solid&&chunk.GetSkyLightLocal(x,y,z)>1)open.push({x,y,z});

    constexpr Int3 directions[6]={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    while(!open.empty()){
        const Int3 cell=open.front();open.pop();
        const uint8_t level=chunk.GetSkyLightLocal(cell.x,cell.y,cell.z);
        if(level<=1)continue;
        const uint8_t next=uint8_t(level-1);
        for(const auto& direction:directions){
            const int x=cell.x+direction.x,y=cell.y+direction.y,z=cell.z+direction.z;
            if(x<0||x>=kChunkSize||y<0||y>=kWorldHeight||z<0||z>=kChunkSize)continue;
            if(blocks_.Get(chunk.GetLocal(x,y,z)).solid||chunk.GetSkyLightLocal(x,y,z)>=next)continue;
            chunk.SetSkyLightLocal(x,y,z,next);open.push({x,y,z});
        }
    }
    chunk.MarkDirty();
}

void World::RebuildLightingAround(ChunkCoord coord){
    const std::array<ChunkCoord,5> affected={coord,ChunkCoord{coord.x-1,coord.z},ChunkCoord{coord.x+1,coord.z},ChunkCoord{coord.x,coord.z-1},ChunkCoord{coord.x,coord.z+1}};
    // Two passes let border light changes feed back into the neighboring chunk.
    for(int pass=0;pass<2;++pass)for(const auto target:affected)if(auto it=chunks_.find(target);it!=chunks_.end())RebuildLighting(*it->second);
}

void World::UpdateStreaming(const Vec3& playerPosition,int renderDistance){
    const ChunkCoord center{FloorDiv(FloorToInt(playerPosition.x),kChunkSize),FloorDiv(FloorToInt(playerPosition.z),kChunkSize)};
    for(int dz=-renderDistance;dz<=renderDistance;++dz)for(int dx=-renderDistance;dx<=renderDistance;++dx){
        if(dx*dx+dz*dz>(renderDistance+1)*(renderDistance+1))continue;
        const ChunkCoord coord{center.x+dx,center.z+dz};
        if(!chunks_.contains(coord)){
            auto chunk=std::make_unique<Chunk>(coord);Generate(*chunk);chunks_.emplace(coord,std::move(chunk));
            for(ChunkCoord neighbor:std::array<ChunkCoord,4>{{{coord.x-1,coord.z},{coord.x+1,coord.z},{coord.x,coord.z-1},{coord.x,coord.z+1}}})if(auto it=chunks_.find(neighbor);it!=chunks_.end())it->second->MarkDirty();
        }
    }
    const int unloadDistance=renderDistance+2;
    for(auto it=chunks_.begin();it!=chunks_.end();){const int dx=it->first.x-center.x,dz=it->first.z-center.z;if(std::abs(dx)>unloadDistance||std::abs(dz)>unloadDistance){unloaded_.push_back(it->first);it=chunks_.erase(it);}else ++it;}
}

BlockId World::GetBlock(int x,int y,int z) const{
    if(y<0||y>=kWorldHeight)return 0;
    const ChunkCoord coord{FloorDiv(x,kChunkSize),FloorDiv(z,kChunkSize)};const auto it=chunks_.find(coord);if(it==chunks_.end())return 0;
    return it->second->GetLocal(FloorMod(x,kChunkSize),y,FloorMod(z,kChunkSize));
}
bool World::IsSolid(int x,int y,int z) const{return blocks_.Get(GetBlock(x,y,z)).solid;}
uint8_t World::GetSkyLight(int x,int y,int z) const{
    if(y>=kWorldHeight)return 15;if(y<0)return 0;
    const ChunkCoord coord{FloorDiv(x,kChunkSize),FloorDiv(z,kChunkSize)};const auto it=chunks_.find(coord);if(it==chunks_.end())return 15;
    return it->second->GetSkyLightLocal(FloorMod(x,kChunkSize),y,FloorMod(z,kChunkSize));
}
uint8_t World::GetBlockLight(int x,int y,int z) const{
    if(y<0||y>=kWorldHeight)return 0;
    const ChunkCoord coord{FloorDiv(x,kChunkSize),FloorDiv(z,kChunkSize)};const auto it=chunks_.find(coord);if(it==chunks_.end())return 0;
    return it->second->GetBlockLightLocal(FloorMod(x,kChunkSize),y,FloorMod(z,kChunkSize));
}
void World::MarkDirtyAt(int wx,int wz){const ChunkCoord coord{FloorDiv(wx,kChunkSize),FloorDiv(wz,kChunkSize)};if(auto it=chunks_.find(coord);it!=chunks_.end())it->second->MarkDirty();}
bool World::SetBlock(int x,int y,int z,BlockId id){
    if(y<0||y>=kWorldHeight)return false;
    const ChunkCoord coord{FloorDiv(x,kChunkSize),FloorDiv(z,kChunkSize)};const auto it=chunks_.find(coord);if(it==chunks_.end())return false;
    const int localX=FloorMod(x,kChunkSize),localZ=FloorMod(z,kChunkSize);if(it->second->GetLocal(localX,y,localZ)==id)return false;
    it->second->SetLocal(localX,y,localZ,id);RebuildLightingAround(coord);
    if(localX==0)MarkDirtyAt(x-1,z);if(localX==kChunkSize-1)MarkDirtyAt(x+1,z);if(localZ==0)MarkDirtyAt(x,z-1);if(localZ==kChunkSize-1)MarkDirtyAt(x,z+1);
    return true;
}

std::optional<RayHit> World::Raycast(const Vec3& origin,const Vec3& direction,double maxDistance) const{
    const Vec3 dir=Normalize(direction);if(Length(dir)<1e-9)return std::nullopt;
    Int3 cell{FloorToInt(origin.x),FloorToInt(origin.y),FloorToInt(origin.z)},previous=cell,normal{};
    const int stepX=dir.x>0?1:-1,stepY=dir.y>0?1:-1,stepZ=dir.z>0?1:-1;
    const auto intBound=[](double value,double delta){if(delta>0)return (std::floor(value+1)-value)/delta;if(delta<0)return (value-std::floor(value))/(-delta);return std::numeric_limits<double>::infinity();};
    double maxX=intBound(origin.x,dir.x),maxY=intBound(origin.y,dir.y),maxZ=intBound(origin.z,dir.z);
    const double deltaX=dir.x==0?std::numeric_limits<double>::infinity():std::abs(1.0/dir.x),deltaY=dir.y==0?std::numeric_limits<double>::infinity():std::abs(1.0/dir.y),deltaZ=dir.z==0?std::numeric_limits<double>::infinity():std::abs(1.0/dir.z);
    double distance=0;
    while(distance<=maxDistance){const BlockId id=GetBlock(cell.x,cell.y,cell.z);if(id!=0&&blocks_.Get(id).solid)return RayHit{cell,previous,normal,id,distance};previous=cell;if(maxX<maxY&&maxX<maxZ){cell.x+=stepX;distance=maxX;maxX+=deltaX;normal={-stepX,0,0};}else if(maxY<maxZ){cell.y+=stepY;distance=maxY;maxY+=deltaY;normal={0,-stepY,0};}else{cell.z+=stepZ;distance=maxZ;maxZ+=deltaZ;normal={0,0,-stepZ};}}
    return std::nullopt;
}

MeshData World::BuildMesh(Chunk& chunk) const{
    MeshData mesh;mesh.revision=chunk.NextRevision();
    const int dimensions[3]={kChunkSize,kWorldHeight,kChunkSize};const ChunkCoord chunkCoord=chunk.Coord();const auto grass=GrassTintForChunk(chunkCoord);
    std::vector<MaskCell> mask(static_cast<size_t>(kWorldHeight*kChunkSize));
    const auto worldBlock=[&](int localX,int localY,int localZ){return GetBlock(chunkCoord.x*kChunkSize+localX,localY,chunkCoord.z*kChunkSize+localZ);};
    for(int axis=0;axis<3;++axis){
        const int u=(axis+1)%3,v=(axis+2)%3;int x[3]={0,0,0},q[3]={0,0,0};q[axis]=1;
        for(x[axis]=-1;x[axis]<dimensions[axis];){
            size_t n=0;
            for(x[v]=0;x[v]<dimensions[v];++x[v])for(x[u]=0;x[u]<dimensions[u];++x[u],++n){
                const BlockId a=x[axis]>=0?worldBlock(x[0],x[1],x[2]):0;const BlockId b=x[axis]<dimensions[axis]-1?worldBlock(x[0]+q[0],x[1]+q[1],x[2]+q[2]):0;
                const bool solidA=blocks_.Get(a).solid,solidB=blocks_.Get(b).solid;MaskCell cell{};
                if(solidA!=solidB){
                    const bool positive=solidA;const BlockId id=solidA?a:b;const Face face=AxisFace(axis,positive);const auto& definition=blocks_.Get(id);const float shade=Shade(face);
                    float red=shade,green=shade,blue=shade;if(definition.tint[static_cast<size_t>(face)]==TintMode::Grass){red*=grass[0];green*=grass[1];blue*=grass[2];}
                    int sample[3]={x[0],x[1],x[2]};if(positive){sample[0]+=q[0];sample[1]+=q[1];sample[2]+=q[2];}
                    const int worldX=chunkCoord.x*kChunkSize+sample[0],worldY=sample[1],worldZ=chunkCoord.z*kChunkSize+sample[2];
                    cell={true,!positive,definition.textureSlices[static_cast<size_t>(face)],PackLitColor(red,green,blue,GetSkyLight(worldX,worldY,worldZ),GetBlockLight(worldX,worldY,worldZ)),face};
                }
                mask[n]=cell;
            }
            ++x[axis];n=0;
            for(int j=0;j<dimensions[v];++j)for(int i=0;i<dimensions[u];){
                const MaskCell cell=mask[n];if(!cell.valid){++i;++n;continue;}
                int width=1;while(i+width<dimensions[u]&&mask[n+width]==cell)++width;int height=1;bool stop=false;while(j+height<dimensions[v]&&!stop){for(int k=0;k<width;++k)if(!(mask[n+k+height*dimensions[u]]==cell)){stop=true;break;}if(!stop)++height;}
                x[u]=i;x[v]=j;int du[3]={0,0,0},dv[3]={0,0,0};du[u]=width;dv[v]=height;
                const auto emit=[&](int ax,int ay,int az,float textureU,float textureV){mesh.vertices.push_back({float(chunkCoord.x*kChunkSize+ax),float(ay),float(chunkCoord.z*kChunkSize+az),textureU,textureV,cell.texture,cell.color});};
                const uint32_t base=static_cast<uint32_t>(mesh.vertices.size());
                if(!cell.back){emit(x[0],x[1],x[2],0,0);emit(x[0]+du[0],x[1]+du[1],x[2]+du[2],float(width),0);emit(x[0]+du[0]+dv[0],x[1]+du[1]+dv[1],x[2]+du[2]+dv[2],float(width),float(height));emit(x[0]+dv[0],x[1]+dv[1],x[2]+dv[2],0,float(height));}
                else{emit(x[0],x[1],x[2],0,0);emit(x[0]+dv[0],x[1]+dv[1],x[2]+dv[2],0,float(height));emit(x[0]+du[0]+dv[0],x[1]+du[1]+dv[1],x[2]+du[2]+dv[2],float(width),float(height));emit(x[0]+du[0],x[1]+du[1],x[2]+du[2],float(width),0);}
                mesh.indices.insert(mesh.indices.end(),{base,base+1,base+2,base,base+2,base+3});for(int row=0;row<height;++row)for(int column=0;column<width;++column)mask[n+column+row*dimensions[u]]={};i+=width;n+=width;
            }
        }
    }
    chunk.ClearDirty();return mesh;
}

std::vector<std::pair<ChunkCoord,MeshData>> World::BuildDirtyMeshes(size_t budget){std::vector<std::pair<ChunkCoord,MeshData>> result;result.reserve(budget);for(auto& [coord,chunk]:chunks_){if(result.size()>=budget)break;if(chunk->Dirty())result.emplace_back(coord,BuildMesh(*chunk));}return result;}
std::vector<ChunkCoord> World::TakeUnloaded(){auto result=std::move(unloaded_);unloaded_.clear();return result;}
}