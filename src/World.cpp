#include "World.h"
#include <fstream>
#include <limits>

namespace mc {
namespace {
size_t Index(int x,int y,int z){return static_cast<size_t>((y*kChunkSize+z)*kChunkSize+x);}
uint32_t PackColor(float r,float g,float b,float a=1.0f){
    auto channel=[](float value){return static_cast<uint32_t>(std::clamp(value,0.0f,1.0f)*255.0f+0.5f);};
    return channel(r)|(channel(g)<<8)|(channel(b)<<16)|(channel(a)<<24);
}
struct MaskCell {bool valid{};bool back{};uint32_t texture{};uint32_t color{};Face face{};bool operator==(const MaskCell&)const=default;};
Face AxisFace(int axis,bool positive){
    if(axis==0)return positive?Face::East:Face::West;
    if(axis==1)return positive?Face::Up:Face::Down;
    return positive?Face::South:Face::North;
}
float Shade(Face face){switch(face){case Face::Up:return 1.0f;case Face::Down:return 0.50f;case Face::North:return 0.80f;case Face::South:return 0.80f;default:return 0.70f;}}
std::array<float,3> GrassTintForChunk(ChunkCoord coord){
    const double noise=std::sin(coord.x*12.9898+coord.z*78.233)*43758.5453;
    const double fract=noise-std::floor(noise);
    double temperature=std::clamp(0.70+(fract-0.5)*0.30,0.0,1.0);
    double humidity=std::clamp(0.75+(0.5-fract)*0.25,0.0,1.0);
    double adjustedHumidity=humidity*temperature;
    [[maybe_unused]] int pixelX=static_cast<int>((1.0-temperature)*255.0);
    [[maybe_unused]] int pixelY=static_cast<int>((1.0-adjustedHumidity)*255.0);
    float dry=static_cast<float>(1.0-adjustedHumidity);
    return {0.28f+0.18f*dry,0.62f+0.20f*static_cast<float>(adjustedHumidity),0.16f+0.08f*dry};
}
std::wstring AbsoluteForMessage(const std::filesystem::path& path){
    std::error_code ec;
    auto absolute=std::filesystem::absolute(path,ec);
    return (ec?path:absolute.lexically_normal()).wstring();
}
}

BlockId Chunk::GetLocal(int x,int y,int z) const {if(x<0||x>=kChunkSize||y<0||y>=kWorldHeight||z<0||z>=kChunkSize)return 0;return blocks_[Index(x,y,z)];}
void Chunk::SetLocal(int x,int y,int z,BlockId id){if(x<0||x>=kChunkSize||y<0||y>=kWorldHeight||z<0||z>=kChunkSize)return;blocks_[Index(x,y,z)]=id;dirty_=true;}

void World::LoadLayerProfile(const std::filesystem::path& file,WarningLog& warnings){
    layers_.clear();
    const std::wstring displayedPath=AbsoluteForMessage(file);
    std::ifstream input(file,std::ios::binary);
    if(!input.is_open()){
        std::error_code ec;
        if(std::filesystem::exists(file,ec))warnings.Add(L"Unreadable world layer profile: "+displayedPath);
        else warnings.Add(L"Missing world layer profile: "+displayedPath);
    }else{
        std::string line;
        size_t lineNumber=0;
        while(std::getline(input,line)){
            ++lineNumber;
            if(lineNumber==1&&line.rfind("\xEF\xBB\xBF",0)==0)line.erase(0,3);
            line=Trim(line);
            if(line.empty()||line[0]=='#')continue;
            auto equals=line.find('=');
            if(equals==std::string::npos){
                warnings.Add(L"Invalid world layer profile line "+std::to_wstring(lineNumber)+L" in "+displayedPath+L": expected block=count");
                continue;
            }
            std::string name=Lower(Trim(line.substr(0,equals)));
            std::string countText=Trim(line.substr(equals+1));
            int count=0;
            try{count=std::stoi(countText);}catch(...){
                warnings.Add(L"Invalid layer count on line "+std::to_wstring(lineNumber)+L" in "+displayedPath+L": "+Widen(countText));
                continue;
            }
            if(count<=0){
                warnings.Add(L"Non-positive layer count on line "+std::to_wstring(lineNumber)+L" in "+displayedPath);
                continue;
            }
            BlockId id=blocks_.Find(name);
            if(id==0){
                const auto definition=file.parent_path().parent_path()/L"blocks"/Widen(name+".block");
                warnings.Add(L"World profile references missing block '"+Widen(name)+L"'. Expected definition: "+AbsoluteForMessage(definition));
                continue;
            }
            layers_.push_back({id,count});
        }
    }

    if(layers_.empty()){
        warnings.Add(L"No valid world layers were loaded from "+displayedPath+L"; using built-in terrain layers");
        const std::array<std::pair<const char*,int>,4> defaults={{{"grass",1},{"dirt",3},{"stone",5},{"bedrock",1}}};
        for(const auto& [name,count]:defaults){
            BlockId id=blocks_.Find(name);
            if(id!=0){
                layers_.push_back({id,count});
            }else{
                const auto definition=file.parent_path().parent_path()/L"blocks"/Widen(std::string(name)+".block");
                warnings.Add(L"Built-in terrain requires missing block definition: "+AbsoluteForMessage(definition));
            }
        }
        if(layers_.empty())warnings.Add(L"Terrain fallback failed because no grass, dirt, stone, or bedrock definitions were discovered");
    }
}

void World::Generate(Chunk& chunk){
    int y=0;
    for(auto it=layers_.rbegin();it!=layers_.rend()&&y<kWorldHeight;++it){
        for(int n=0;n<it->count&&y<kWorldHeight;++n,++y)
            for(int z=0;z<kChunkSize;++z)for(int x=0;x<kChunkSize;++x)chunk.SetLocal(x,y,z,it->id);
    }
    chunk.MarkDirty();
}

void World::UpdateStreaming(const Vec3& playerPosition,int renderDistance){
    ChunkCoord center{FloorDiv(FloorToInt(playerPosition.x),kChunkSize),FloorDiv(FloorToInt(playerPosition.z),kChunkSize)};
    for(int dz=-renderDistance;dz<=renderDistance;++dz)for(int dx=-renderDistance;dx<=renderDistance;++dx){
        if(dx*dx+dz*dz>(renderDistance+1)*(renderDistance+1))continue;
        ChunkCoord coord{center.x+dx,center.z+dz};
        if(!chunks_.contains(coord)){
            auto chunk=std::make_unique<Chunk>(coord);Generate(*chunk);chunks_.emplace(coord,std::move(chunk));
            for(ChunkCoord neighbor:std::array<ChunkCoord,4>{{{coord.x-1,coord.z},{coord.x+1,coord.z},{coord.x,coord.z-1},{coord.x,coord.z+1}}})if(auto it=chunks_.find(neighbor);it!=chunks_.end())it->second->MarkDirty();
        }
    }
    const int unloadDistance=renderDistance+2;
    for(auto it=chunks_.begin();it!=chunks_.end();){
        int dx=it->first.x-center.x,dz=it->first.z-center.z;
        if(std::abs(dx)>unloadDistance||std::abs(dz)>unloadDistance){unloaded_.push_back(it->first);it=chunks_.erase(it);}else ++it;
    }
}

BlockId World::GetBlock(int x,int y,int z) const {
    if(y<0||y>=kWorldHeight)return 0;
    ChunkCoord coord{FloorDiv(x,kChunkSize),FloorDiv(z,kChunkSize)};auto it=chunks_.find(coord);if(it==chunks_.end())return 0;
    return it->second->GetLocal(FloorMod(x,kChunkSize),y,FloorMod(z,kChunkSize));
}
bool World::IsSolid(int x,int y,int z) const {return blocks_.Get(GetBlock(x,y,z)).solid;}
void World::MarkDirtyAt(int wx,int wz){ChunkCoord coord{FloorDiv(wx,kChunkSize),FloorDiv(wz,kChunkSize)};if(auto it=chunks_.find(coord);it!=chunks_.end())it->second->MarkDirty();}
bool World::SetBlock(int x,int y,int z,BlockId id){
    if(y<0||y>=kWorldHeight)return false;ChunkCoord coord{FloorDiv(x,kChunkSize),FloorDiv(z,kChunkSize)};auto it=chunks_.find(coord);if(it==chunks_.end())return false;
    int lx=FloorMod(x,kChunkSize),lz=FloorMod(z,kChunkSize);if(it->second->GetLocal(lx,y,lz)==id)return false;
    it->second->SetLocal(lx,y,lz,id);if(lx==0)MarkDirtyAt(x-1,z);if(lx==kChunkSize-1)MarkDirtyAt(x+1,z);if(lz==0)MarkDirtyAt(x,z-1);if(lz==kChunkSize-1)MarkDirtyAt(x,z+1);return true;
}

std::optional<RayHit> World::Raycast(const Vec3& origin,const Vec3& direction,double maxDistance) const {
    Vec3 dir=Normalize(direction);if(Length(dir)<1e-9)return std::nullopt;
    Int3 cell{FloorToInt(origin.x),FloorToInt(origin.y),FloorToInt(origin.z)},previous=cell,normal{};
    int stepX=dir.x>0?1:-1,stepY=dir.y>0?1:-1,stepZ=dir.z>0?1:-1;
    auto intBound=[](double s,double ds){if(ds>0)return (std::floor(s+1)-s)/ds;if(ds<0)return (s-std::floor(s))/(-ds);return std::numeric_limits<double>::infinity();};
    double tMaxX=intBound(origin.x,dir.x),tMaxY=intBound(origin.y,dir.y),tMaxZ=intBound(origin.z,dir.z);
    double tDeltaX=dir.x==0?std::numeric_limits<double>::infinity():std::abs(1.0/dir.x);
    double tDeltaY=dir.y==0?std::numeric_limits<double>::infinity():std::abs(1.0/dir.y);
    double tDeltaZ=dir.z==0?std::numeric_limits<double>::infinity():std::abs(1.0/dir.z);
    double t=0;
    while(t<=maxDistance){
        BlockId id=GetBlock(cell.x,cell.y,cell.z);if(id!=0&&blocks_.Get(id).solid)return RayHit{cell,previous,normal,id,t};
        previous=cell;
        if(tMaxX<tMaxY&&tMaxX<tMaxZ){cell.x+=stepX;t=tMaxX;tMaxX+=tDeltaX;normal={-stepX,0,0};}
        else if(tMaxY<tMaxZ){cell.y+=stepY;t=tMaxY;tMaxY+=tDeltaY;normal={0,-stepY,0};}
        else{cell.z+=stepZ;t=tMaxZ;tMaxZ+=tDeltaZ;normal={0,0,-stepZ};}
    }return std::nullopt;
}

MeshData World::BuildMesh(Chunk& chunk) const {
    MeshData mesh;mesh.revision=chunk.NextRevision();
    const int dims[3]={kChunkSize,kWorldHeight,kChunkSize};
    const ChunkCoord chunkCoord=chunk.Coord();const auto grass=GrassTintForChunk(chunkCoord);
    std::vector<MaskCell> mask(static_cast<size_t>(kWorldHeight*kChunkSize));
    auto worldBlock=[&](int lx,int ly,int lz){return GetBlock(chunkCoord.x*kChunkSize+lx,ly,chunkCoord.z*kChunkSize+lz);};
    for(int d=0;d<3;++d){
        int u=(d+1)%3,v=(d+2)%3;int x[3]={0,0,0},q[3]={0,0,0};q[d]=1;
        for(x[d]=-1;x[d]<dims[d];){
            size_t n=0;
            for(x[v]=0;x[v]<dims[v];++x[v])for(x[u]=0;x[u]<dims[u];++x[u],++n){
                BlockId a=x[d]>=0?worldBlock(x[0],x[1],x[2]):0;
                BlockId b=x[d]<dims[d]-1?worldBlock(x[0]+q[0],x[1]+q[1],x[2]+q[2]):0;
                bool solidA=blocks_.Get(a).solid,solidB=blocks_.Get(b).solid;MaskCell cell{};
                if(solidA!=solidB){
                    bool positive=solidA;BlockId id=solidA?a:b;Face face=AxisFace(d,positive);const auto& definition=blocks_.Get(id);
                    float shade=Shade(face),r=shade,g=shade,blue=shade;
                    if(definition.tint[static_cast<size_t>(face)]==TintMode::Grass){r*=grass[0];g*=grass[1];blue*=grass[2];}
                    cell={true,!positive,definition.textureSlices[static_cast<size_t>(face)],PackColor(r,g,blue),face};
                }mask[n]=cell;
            }
            ++x[d];n=0;
            for(int j=0;j<dims[v];++j)for(int i=0;i<dims[u];){
                const MaskCell cell=mask[n];if(!cell.valid){++i;++n;continue;}
                int width=1;while(i+width<dims[u]&&mask[n+width]==cell)++width;
                int height=1;bool stop=false;while(j+height<dims[v]&&!stop){for(int k=0;k<width;++k)if(!(mask[n+k+height*dims[u]]==cell)){stop=true;break;}if(!stop)++height;}
                x[u]=i;x[v]=j;int du[3]={0,0,0},dv[3]={0,0,0};du[u]=width;dv[v]=height;
                auto emit=[&](int ax,int ay,int az,float tu,float tv){mesh.vertices.push_back({float(chunkCoord.x*kChunkSize+ax),float(ay),float(chunkCoord.z*kChunkSize+az),tu,tv,cell.texture,cell.color});};
                uint32_t base=static_cast<uint32_t>(mesh.vertices.size());
                if(!cell.back){emit(x[0],x[1],x[2],0,0);emit(x[0]+du[0],x[1]+du[1],x[2]+du[2],float(width),0);emit(x[0]+du[0]+dv[0],x[1]+du[1]+dv[1],x[2]+du[2]+dv[2],float(width),float(height));emit(x[0]+dv[0],x[1]+dv[1],x[2]+dv[2],0,float(height));}
                else{emit(x[0],x[1],x[2],0,0);emit(x[0]+dv[0],x[1]+dv[1],x[2]+dv[2],0,float(height));emit(x[0]+du[0]+dv[0],x[1]+du[1]+dv[1],x[2]+du[2]+dv[2],float(width),float(height));emit(x[0]+du[0],x[1]+du[1],x[2]+du[2],float(width),0);}
                mesh.indices.insert(mesh.indices.end(),{base,base+1,base+2,base,base+2,base+3});
                for(int l=0;l<height;++l)for(int k=0;k<width;++k)mask[n+k+l*dims[u]]={};i+=width;n+=width;
            }
        }
    }
    chunk.ClearDirty();return mesh;
}

std::vector<std::pair<ChunkCoord,MeshData>> World::BuildDirtyMeshes(size_t budget){
    std::vector<std::pair<ChunkCoord,MeshData>> out;out.reserve(budget);
    for(auto& [coord,chunk]:chunks_){if(out.size()>=budget)break;if(chunk->Dirty())out.emplace_back(coord,BuildMesh(*chunk));}return out;
}
std::vector<ChunkCoord> World::TakeUnloaded(){auto out=std::move(unloaded_);unloaded_.clear();return out;}
}
