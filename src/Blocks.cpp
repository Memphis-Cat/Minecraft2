#include "Blocks.h"
#include <charconv>
#include <set>

namespace mc {
namespace {
bool ParseBool(const std::string& value,bool fallback){
    const auto text=Lower(Trim(value));
    if(text=="true"||text=="1"||text=="yes")return true;
    if(text=="false"||text=="0"||text=="no")return false;
    return fallback;
}
float ParseFloat(const std::string& value,float fallback){try{return std::stof(value);}catch(...){return fallback;}}
int ParseInt(const std::string& value,int fallback){try{return std::stoi(value);}catch(...){return fallback;}}
TintMode ParseTint(const std::string& value){return Lower(Trim(value))=="grass"?TintMode::Grass:TintMode::None;}
constexpr size_t I(Face face){return static_cast<size_t>(face);}
std::wstring AbsoluteForMessage(const std::filesystem::path& path){std::error_code ec;const auto absolute=std::filesystem::absolute(path,ec);return (ec?path:absolute.lexically_normal()).wstring();}

void ApplyBuiltInDefinition(BlockDefinition& block){
    // Required gameplay values remain correct even without optional .block metadata files.
    if(block.name=="grass"){
        block.hardness=0.6f;
        block.breakTicks=18; // 0.90 seconds at 20 ticks per second.
        block.breakable=true;
        block.solid=true;
        block.textureNames.fill("grass_side");
        block.textureNames[I(Face::Up)]="grass_top";
        block.textureNames[I(Face::Down)]="dirt";
        block.tint.fill(TintMode::None);
        block.tint[I(Face::Up)]=TintMode::Grass;
        for(Face face:{Face::West,Face::East,Face::North,Face::South})block.tint[I(face)]=TintMode::Grass;
    }else if(block.name=="dirt"){
        block.hardness=0.5f;
        block.breakTicks=15; // 0.75 seconds.
        block.breakable=true;
        block.solid=true;
        block.textureNames.fill("dirt");
    }else if(block.name=="stone"){
        block.hardness=1.5f;
        block.breakTicks=150; // 7.50 seconds.
        block.breakable=true;
        block.solid=true;
        block.textureNames.fill("stone");
    }else if(block.name=="bedrock"){
        block.hardness=-1.0f;
        block.breakTicks=-1;
        block.breakable=false;
        block.solid=true;
        block.textureNames.fill("bedrock");
    }
}
}

void BlockRegistry::Discover(const std::filesystem::path& root,WarningLog& warnings){
    blocks_.clear();
    byName_.clear();

    BlockDefinition air;
    air.id=0;
    air.name="air";
    air.solid=false;
    air.breakable=false;
    air.breakTicks=-1;
    blocks_.push_back(air);
    byName_["air"]=0;

    const auto textureDir=root/L"textures"/L"blocks";
    const auto metadataDir=root/L"blocks";
    std::set<std::string> names;
    std::unordered_map<std::string,std::set<std::string>> discovered;
    std::error_code ec;

    if(std::filesystem::is_directory(textureDir,ec)){
        for(const auto& entry:std::filesystem::directory_iterator(textureDir)){
            if(!entry.is_regular_file()||Lower(entry.path().extension().string())!=".png")continue;
            const std::string stem=Lower(entry.path().stem().string());
            if(stem=="fallback"||stem.rfind("destroy_stage_",0)==0)continue;
            std::string base=stem;
            for(const std::string suffix:{"_bottom","_north","_south","_east","_west","_side","_top"}){
                if(base.size()>suffix.size()&&base.ends_with(suffix)){
                    base.resize(base.size()-suffix.size());
                    break;
                }
            }
            names.insert(base);
            discovered[base].insert(stem);
        }
    }else{
        warnings.Add(L"Missing block texture directory: "+AbsoluteForMessage(textureDir));
    }

    ec.clear();
    if(std::filesystem::is_directory(metadataDir,ec)){
        for(const auto& entry:std::filesystem::directory_iterator(metadataDir)){
            if(entry.is_regular_file()&&Lower(entry.path().extension().string())==".block")names.insert(Lower(entry.path().stem().string()));
        }
    }

    if(names.empty())warnings.Add(L"No blocks were discovered in: "+AbsoluteForMessage(textureDir));

    for(const auto& name:names){
        BlockDefinition block;
        block.id=static_cast<BlockId>(blocks_.size());
        block.name=name;

        const auto has=[&](const std::string& texture){
            const auto it=discovered.find(name);
            return it!=discovered.end()&&it->second.contains(texture);
        };

        const std::string base=has(name)?name:"fallback";
        block.textureNames.fill(base);
        if(has(name+"_side"))for(Face face:{Face::West,Face::East,Face::North,Face::South})block.textureNames[I(face)]=name+"_side";
        if(has(name+"_top"))block.textureNames[I(Face::Up)]=name+"_top";
        if(has(name+"_bottom"))block.textureNames[I(Face::Down)]=name+"_bottom";
        if(has(name+"_west"))block.textureNames[I(Face::West)]=name+"_west";
        if(has(name+"_east"))block.textureNames[I(Face::East)]=name+"_east";
        if(has(name+"_north"))block.textureNames[I(Face::North)]=name+"_north";
        if(has(name+"_south"))block.textureNames[I(Face::South)]=name+"_south";

        ApplyBuiltInDefinition(block);

        const auto properties=ReadProperties(metadataDir/Widen(name+".block"));
        const auto get=[&](std::string_view key)->std::optional<std::string>{
            const auto it=properties.find(std::string(key));
            return it==properties.end()?std::nullopt:std::optional<std::string>(it->second);
        };

        if(auto value=get("hardness"))block.hardness=ParseFloat(*value,block.hardness);
        if(auto value=get("break_ticks"))block.breakTicks=ParseInt(*value,block.breakTicks);
        else if(name!="grass"&&name!="dirt"&&name!="stone"&&name!="bedrock")block.breakTicks=block.hardness<0?-1:std::max(1,static_cast<int>(std::lround(double(block.hardness)*30.0)));
        if(auto value=get("solid"))block.solid=ParseBool(*value,block.solid);
        block.breakable=block.hardness>=0&&block.breakTicks>0;
        if(auto value=get("breakable"))block.breakable=ParseBool(*value,block.breakable);

        if(auto value=get("texture"))block.textureNames.fill(Lower(*value));
        if(auto value=get("side"))for(Face face:{Face::West,Face::East,Face::North,Face::South})block.textureNames[I(face)]=Lower(*value);
        if(auto value=get("top"))block.textureNames[I(Face::Up)]=Lower(*value);
        if(auto value=get("bottom"))block.textureNames[I(Face::Down)]=Lower(*value);
        if(auto value=get("west"))block.textureNames[I(Face::West)]=Lower(*value);
        if(auto value=get("east"))block.textureNames[I(Face::East)]=Lower(*value);
        if(auto value=get("north"))block.textureNames[I(Face::North)]=Lower(*value);
        if(auto value=get("south"))block.textureNames[I(Face::South)]=Lower(*value);
        if(auto value=get("tint"))block.tint.fill(ParseTint(*value));
        if(auto value=get("tint_side"))for(Face face:{Face::West,Face::East,Face::North,Face::South})block.tint[I(face)]=ParseTint(*value);
        if(auto value=get("tint_top"))block.tint[I(Face::Up)]=ParseTint(*value);
        if(auto value=get("tint_bottom"))block.tint[I(Face::Down)]=ParseTint(*value);

        byName_[name]=block.id;
        blocks_.push_back(std::move(block));
    }
}

void BlockRegistry::ResolveTextures(const TexturePack& pack){for(auto& block:blocks_)for(size_t face=0;face<6;++face)block.textureSlices[face]=pack.Slice(block.textureNames[face]);}
const BlockDefinition& BlockRegistry::Get(BlockId id) const{return id<blocks_.size()?blocks_[id]:blocks_[0];}
BlockId BlockRegistry::Find(std::string_view name) const{const auto it=byName_.find(Lower(std::string(name)));return it==byName_.end()?0:it->second;}
std::vector<std::string> BlockRegistry::RequiredTextureNames() const{std::vector<std::string> result;for(const auto& block:blocks_)for(const auto& texture:block.textureNames)if(!texture.empty())result.push_back(texture);return result;}
}
