#include "World.h"
#include <fstream>
#include <limits>

namespace mc {
namespace {
size_t Index(int x,int y,int z){return static_cast<size_t>((y*kChunkSize+z)*kChunkSize+x);} 
uint32_t PackColor(float r,float g,float b,float a=1.0f){
    auto c=[](float v){return static_cast<uint32_t>(std::clamp(v,0.0f,1.0f)*255.0f+0.5f);};
    return c(r)|(c(g)<<8)|(c(b)<<16)|(c(a)<<24);
}
struct MaskCell { bool valid{}; bool back{}; uint32_t texture{}; uint32_t color{}; Face face{}; bool operator==(const MaskCell&)const=default; };
Face AxisFace(int axis,bool positive){
    if(axis==0)return positive?Face::East:Face::West;
    if(axis==1)return positive?Face::Up:Face::Down;
    return positive?Face::South:Face::North;
}
float Shade(Face f){ switch(f){case Face::Up:return 1.0f; case Face::Down:return 0.50f; case Face::North:return 0.80f; case Face::South:return 0.80f; default:return 0.70f;} }
std::array<float,3> GrassTintForChunk(ChunkCoord c){
    const double n=std::sin(c.x*12.9898+c.z*78.233)*43758.5453;
    const double fract=n-std::floor(n);
    double temperature=std::clamp(0.70+(fract-0.5)*0.30,0.0,1.0);
    double humidity=std::clamp(0.75+(0.5-fract)*0.25,0.0,1.0);
    double adjustedHumidity=humidity*temperature;
    [[maybe_unused]] int pixelX=static_cast<int>((1.0-temperature)*255.0);
    [[maybe_unused]] int pixelY=static_cast<int>((1.0-adjustedHumidity)*255.0);
    // Approximation used until a grass colormap PNG is supplied; coordinates above match Minecraft's lookup formula.
    float dry=static_cast<float>(1.0-adjustedHumidity);
    return {0.28f+0.18f*dry,0.62f+0.20f*static_cast<float>(adjustedHumidity),0.16f+0.08f*dry};
}
}

BlockId Chunk::GetLocal(int x,int y,int z) const { if(x<0||x>=kChunkSize||y<0||y>=kWorldHeight||z<0||z>=kChunkSize)return 0; return blocks_[Index(x,y,z)]; }
void Chunk::SetLocal(int x,int y,int z,BlockId id){ if(x<0||x>=kChunkSize||y<0||y>=kWorldHeight||z<0||z>=kChunkSize)return; blocks_[Index(x,y,z)]=id; dirty_=true; }

void World::LoadLayerProfile(const std::filesystem::path& file,WarningLog& warnings){
    layers_.clear(); std::ifstream in(file); std::string line;
    while(std::getline(in,line)){
        line=Trim(line); if(line.empty()||line[0]=='#')continue;
        auto eq=line.find('='); if(eq==std::string::npos)continue;
        std::string name=Lower(Trim(line.substr(0,eq))); int count=0;
        try{count=std::stoi(Trim(line.substr(eq+1)));}catch(...){count=0;}
        BlockId id=blocks_.Find(name);
        if(id==0){warnings.Add(Widen(Lower(name)+" block referenced by worldgen but not discovered"));continue;}
        if(count>0)layers_.push_back({id,count});
    }
    if(layers_.empty()) warnings.Add(L"World layer profile is empty; generated chunks will contain air");
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
        ChunkCoord c{center.x+dx,center.z+dz};
        if(!chunks_.contains(c)){
            auto chunk=std::make_unique<Chunk>(c); Generate(*chunk); chunks_.emplace(c,std::move(chunk));
            for(ChunkCoord n:std::array<ChunkCoord,4>{{{c.x-1,c.z},{c.x+1,c.z},{c.x,c.z-1},{c.x,c.z+1}}}) if(auto it=chunks_.find(n);it!=chunks_.end())it->second->MarkDirty();
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
    ChunkCoord c{FloorDiv(x,kChunkSize),FloorDiv(z,kChunkSize)}; auto it=chunks_.find(c); if(it==chunks_.end())return 0;
    return it->second->GetLocal(FloorMod(x,kChunkSize),y,FloorMod(z,kChunkSize));
}
bool World::IsSolid(int x,int y,int z) const { return blocks_.Get(GetBlock(x,y,z)).solid; }
void World::MarkDirtyAt(int wx,int wz){ChunkCoord c{FloorDiv(wx,kChunkSize),FloorDiv(wz,kChunkSize)};if(auto it=chunks_.find(c);it!=chunks_.end())it->second->MarkDirty();}
bool World::SetBlock(int x,int y,int z,BlockId id){
    if(y<0||y>=kWorldHeight)return false; ChunkCoord c{FloorDiv(x,kChunkSize),FloorDiv(z,kChunkSize)}; auto it=chunks_.find(c); if(it==chunks_.end())return false;
    int lx=FloorMod(x,kChunkSize),lz=FloorMod(z,kChunkSize); if(it->second->GetLocal(lx,y,lz)==id)return false;
    it->second->SetLocal(lx,y,lz,id); if(lx==0)MarkDirtyAt(x-1,z);if(lx==kChunkSize-1)MarkDirtyAt(x+1,z);if(lz==0)MarkDirtyAt(x,z-1);if(lz==kChunkSize-1)MarkDirtyAt(x,z+1); return true;
}

std::optional<RayHit> World::Raycast(const Vec3& origin,const Vec3& direction,double maxDistance) const {
    Vec3 dir=Normalize(direction); if(Length(dir)<1e-9)return std::nullopt;
    Int3 cell{FloorToInt(origin.x),FloorToInt(origin.y),FloorToInt(origin.z)}, previous=cell, normal{};
    int stepX=dir.x>0?1:-1,stepY=dir.y>0?1:-1,stepZ=dir.z>0?1:-1;
    auto intBound=[](double s,double ds){ if(ds>0)return (std::floor(s+1)-s)/ds; if(ds<0)return (s-std::floor(s))/(-ds); return std::numeric_limits<double>::infinity();};
    double tMaxX=intBound(origin.x,dir.x),tMaxY=intBound(origin.y,dir.y),tMaxZ=intBound(origin.z,dir.z);
    double tDeltaX=dir.x==0?std::numeric_limits<double>::infinity():std::abs(1.0/dir.x);
    double tDeltaY=dir.y==0?std::numeric_limits<double>::infinity():std::abs(1.0/dir.y);
    double tDeltaZ=dir.z==0?std::numeric_limits<double>::infinity():std::abs(1.0/dir.z);
    double t=0;
    while(t<=maxDistance){
        BlockId id=GetBlock(cell.x,cell.y,cell.z); if(id!=0&&blocks_.Get(id).solid)return RayHit{cell,previous,normal,id,t};
        previous=cell;
        if(tMaxX<tMaxY&&tMaxX<tMaxZ){cell.x+=stepX;t=tMaxX;tMaxX+=tDeltaX;normal={-stepX,0,0};}
        else if(tMaxY<tMaxZ){cell.y+=stepY;t=tMaxY;tMaxY+=tDeltaY;normal={0,-stepY,0};}
        else{cell.z+=stepZ;t=tMaxZ;tMaxZ+=tDeltaZ;normal={0,0,-stepZ};}
    } return std::nullopt;
}

MeshData World::BuildMesh(Chunk& chunk) const {
    MeshData mesh; mesh.revision=chunk.NextRevision();
    const int dims[3]={kChunkSize,kWorldHeight,kChunkSize};
    const ChunkCoord cc=chunk.Coord(); const auto grass=GrassTintForChunk(cc);
    std::vector<MaskCell> mask(static_cast<size_t>(kWorldHeight*kChunkSize));
    auto worldBlock=[&](int lx,int ly,int lz){return GetBlock(cc.x*kChunkSize+lx,ly,cc.z*kChunkSize+lz);};
    for(int d=0;d<3;++d){
        int u=(d+1)%3,v=(d+2)%3; int x[3]={0,0,0},q[3]={0,0,0}; q[d]=1;
        for(x[d]=-1;x[d]<dims[d];){
            size_t n=0;
            for(x[v]=0;x[v]<dims[v];++x[v])for(x[u]=0;x[u]<dims[u];++x[u],++n){
                BlockId a=x[d]>=0?worldBlock(x[0],x[1],x[2]):0;
                BlockId b=x[d]<dims[d]-1?worldBlock(x[0]+q[0],x[1]+q[1],x[2]+q[2]):0;
                bool sa=blocks_.Get(a).solid,sb=blocks_.Get(b).solid; MaskCell m{};
                if(sa!=sb){
                    bool positive=sa; BlockId id=sa?a:b; Face face=AxisFace(d,positive); const auto& def=blocks_.Get(id);
                    float shade=Shade(face),r=shade,g=shade,bl=shade;
                    if(def.tint[static_cast<size_t>(face)]==TintMode::Grass){r*=grass[0];g*=grass[1];bl*=grass[2];}
                    m={true,!positive,def.textureSlices[static_cast<size_t>(face)],PackColor(r,g,bl),face};
                } mask[n]=m;
            }
            ++x[d]; n=0;
            for(int j=0;j<dims[v];++j)for(int i=0;i<dims[u];){
                const MaskCell c=mask[n]; if(!c.valid){++i;++n;continue;}
                int w=1; while(i+w<dims[u]&&mask[n+w]==c)++w;
                int h=1; bool stop=false; while(j+h<dims[v]&&!stop){for(int k=0;k<w;++k)if(!(mask[n+k+h*dims[u]]==c)){stop=true;break;}if(!stop)++h;}
                x[u]=i;x[v]=j;int du[3]={0,0,0},dv[3]={0,0,0};du[u]=w;dv[v]=h;
                auto emit=[&](int ax,int ay,int az,float tu,float tv){mesh.vertices.push_back({float(cc.x*kChunkSize+ax),float(ay),float(cc.z*kChunkSize+az),tu,tv,c.texture,c.color});};
                uint32_t base=static_cast<uint32_t>(mesh.vertices.size());
                if(!c.back){emit(x[0],x[1],x[2],0,0);emit(x[0]+du[0],x[1]+du[1],x[2]+du[2],float(w),0);emit(x[0]+du[0]+dv[0],x[1]+du[1]+dv[1],x[2]+du[2]+dv[2],float(w),float(h));emit(x[0]+dv[0],x[1]+dv[1],x[2]+dv[2],0,float(h));}
                else{emit(x[0],x[1],x[2],0,0);emit(x[0]+dv[0],x[1]+dv[1],x[2]+dv[2],0,float(h));emit(x[0]+du[0]+dv[0],x[1]+du[1]+dv[1],x[2]+du[2]+dv[2],float(w),float(h));emit(x[0]+du[0],x[1]+du[1],x[2]+du[2],float(w),0);}
                mesh.indices.insert(mesh.indices.end(),{base,base+1,base+2,base,base+2,base+3});
                for(int l=0;l<h;++l)for(int k=0;k<w;++k)mask[n+k+l*dims[u]]={}; i+=w;n+=w;
            }
        }
    }
    chunk.ClearDirty(); return mesh;
}

std::vector<std::pair<ChunkCoord,MeshData>> World::BuildDirtyMeshes(size_t budget){
    std::vector<std::pair<ChunkCoord,MeshData>> out; out.reserve(budget);
    for(auto& [coord,chunk]:chunks_){if(out.size()>=budget)break;if(chunk->Dirty())out.emplace_back(coord,BuildMesh(*chunk));} return out;
}
std::vector<ChunkCoord> World::TakeUnloaded(){auto out=std::move(unloaded_);unloaded_.clear();return out;}
}
