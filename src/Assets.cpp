#include "Assets.h"
#include <Windows.h>
#include <fstream>
#include <sstream>

namespace mc {
namespace {
std::wstring AbsoluteForMessage(const std::filesystem::path& path){
    std::error_code ec;
    auto absolute=std::filesystem::absolute(path,ec);
    return (ec?path:absolute.lexically_normal()).wstring();
}

std::filesystem::path ExecutableDirectory(){
    std::wstring buffer(32768,L'\0');
    DWORD length=GetModuleFileNameW(nullptr,buffer.data(),static_cast<DWORD>(buffer.size()));
    if(length==0||length>=buffer.size())return std::filesystem::current_path();
    buffer.resize(length);
    return std::filesystem::path(buffer).parent_path();
}

int AssetRootScore(const std::filesystem::path& root){
    std::error_code ec;
    int score=0;
    if(std::filesystem::is_directory(root/L"textures"/L"blocks",ec))score+=4;
    ec.clear();
    if(std::filesystem::is_directory(root/L"blocks",ec))score+=2;
    ec.clear();
    if(std::filesystem::is_regular_file(root/L"worldgen"/L"flat_overworld.layers",ec))score+=1;
    return score;
}
}

void WarningLog::Add(std::wstring message){if(unique_.insert(message).second)messages_.push_back(std::move(message));}
std::wstring WarningLog::Format() const {
    std::wostringstream out;
    out << L"Minecraft2 found the following asset problems.\n"
        << L"Every missing or unreadable path is listed below.\n\n";
    for(const auto& message:messages_)out << L"- " << message << L'\n';
    return out.str();
}
std::wstring Widen(std::string_view text){
    if(text.empty())return {};
    int n=MultiByteToWideChar(CP_UTF8,0,text.data(),static_cast<int>(text.size()),nullptr,0);
    std::wstring out(n,L'\0');
    MultiByteToWideChar(CP_UTF8,0,text.data(),static_cast<int>(text.size()),out.data(),n);return out;
}
std::string Narrow(std::wstring_view text){
    if(text.empty())return {};
    int n=WideCharToMultiByte(CP_UTF8,0,text.data(),static_cast<int>(text.size()),nullptr,0,nullptr,nullptr);
    std::string out(n,'\0');
    WideCharToMultiByte(CP_UTF8,0,text.data(),static_cast<int>(text.size()),out.data(),n,nullptr,nullptr);return out;
}
std::string Trim(std::string value){auto ws=[](unsigned char c){return std::isspace(c)!=0;};while(!value.empty()&&ws(value.front()))value.erase(value.begin());while(!value.empty()&&ws(value.back()))value.pop_back();return value;}
std::string Lower(std::string value){std::transform(value.begin(),value.end(),value.begin(),[](unsigned char c){return char(std::tolower(c));});return value;}

std::filesystem::path FindAssetRoot(){
    const auto exeDir=ExecutableDirectory();
    const auto cwd=std::filesystem::current_path();
    const std::array<std::filesystem::path,8> candidates={
        exeDir/L"assets",
        exeDir/L"assets"/L"assets",
        exeDir/L".."/L"assets"/L"assets",
        cwd/L"assets"/L"assets",
        cwd/L"assets",
        cwd/L".."/L"assets"/L"assets",
        cwd/L".."/L"assets",
        exeDir
    };

    std::filesystem::path best=candidates.front();
    int bestScore=-1;
    for(const auto& candidate:candidates){
        int score=AssetRootScore(candidate);
        if(score>bestScore){best=candidate;bestScore=score;}
    }

    std::error_code ec;
    auto canonical=std::filesystem::weakly_canonical(best,ec);
    return ec?best.lexically_normal():canonical;
}

std::map<std::string,std::string> ReadProperties(const std::filesystem::path& path){
    std::map<std::string,std::string> result;std::ifstream in(path);std::string line;
    while(std::getline(in,line)){
        line=Trim(line);if(line.empty()||line[0]=='#')continue;
        auto eq=line.find('=');if(eq==std::string::npos)continue;
        result[Lower(Trim(line.substr(0,eq)))]=Trim(line.substr(eq+1));
    }return result;
}

void TexturePack::Build(const std::filesystem::path& assetRoot,const std::vector<std::string>& requiredNames,WarningLog& warnings){
    slices_.clear();slicePaths_.clear();destroyStages_.fill(0);
    const auto dir=assetRoot/L"textures"/L"blocks";
    std::map<std::string,std::filesystem::path> found;
    std::error_code ec;
    if(std::filesystem::is_directory(dir,ec)){
        for(const auto& entry:std::filesystem::directory_iterator(dir)){
            if(!entry.is_regular_file()||Lower(entry.path().extension().string())!=".png")continue;
            found[Lower(entry.path().stem().string())]=entry.path();
        }
    }else{
        warnings.Add(L"Missing directory: "+AbsoluteForMessage(dir));
    }

    auto fallbackIt=found.find("fallback");
    if(fallbackIt==found.end()){
        slicePaths_.push_back({});
        warnings.Add(L"Missing texture: "+AbsoluteForMessage(dir/L"fallback.png")+L" (using an in-memory checker)");
    }else{
        slicePaths_.push_back(fallbackIt->second);
    }
    slices_["fallback"]=0;

    for(const auto& [name,path]:found){
        if(name=="fallback")continue;
        if(slicePaths_.size()>=65535)break;
        slices_[name]=static_cast<uint16_t>(slicePaths_.size());slicePaths_.push_back(path);
    }

    for(const auto& raw:requiredNames){
        const auto name=Lower(raw);
        if(!slices_.contains(name)){
            slices_[name]=0;
            warnings.Add(L"Missing texture: "+AbsoluteForMessage(dir/Widen(name+".png")));
        }
    }

    for(int i=0;i<10;++i){
        const std::string name="destroy_stage_"+std::to_string(i);
        auto it=slices_.find(name);
        if(it==slices_.end()){
            slices_[name]=0;destroyStages_[i]=0;
            warnings.Add(L"Missing texture: "+AbsoluteForMessage(dir/Widen(name+".png")));
        }else{
            destroyStages_[i]=it->second;
        }
    }
}
uint16_t TexturePack::Slice(std::string_view textureName) const {auto it=slices_.find(Lower(std::string(textureName)));return it==slices_.end()?0:it->second;}
}
