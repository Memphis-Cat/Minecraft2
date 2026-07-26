#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cctype>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <limits>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace mc {
constexpr int kChunkSize = 16;
constexpr int kWorldHeight = 64;
constexpr double kReach = 4.5;
constexpr double kTicksPerSecond = 20.0;
constexpr double kTickSeconds = 1.0 / kTicksPerSecond;

struct Vec3 {
    double x{}, y{}, z{};
    Vec3 operator+(const Vec3& o) const { return {x+o.x,y+o.y,z+o.z}; }
    Vec3 operator-(const Vec3& o) const { return {x-o.x,y-o.y,z-o.z}; }
    Vec3 operator*(double s) const { return {x*s,y*s,z*s}; }
    Vec3& operator+=(const Vec3& o){ x+=o.x; y+=o.y; z+=o.z; return *this; }
};
inline double Dot(const Vec3&a,const Vec3&b){return a.x*b.x+a.y*b.y+a.z*b.z;}
inline double Length(const Vec3&v){return std::sqrt(Dot(v,v));}
inline Vec3 Normalize(const Vec3&v){double l=Length(v); return l>1e-9?v*(1.0/l):Vec3{};}

struct Int3 { int x{},y{},z{}; bool operator==(const Int3&) const = default; };
struct ChunkCoord { int x{},z{}; bool operator==(const ChunkCoord&) const = default; };
struct ChunkCoordHash { size_t operator()(const ChunkCoord& c) const noexcept { return (uint64_t(uint32_t(c.x))*0x9E3779B185EBCA87ull)^uint32_t(c.z); } };

inline int FloorDiv(int value,int divisor){ int q=value/divisor,r=value%divisor; return r<0?q-1:q; }
inline int FloorMod(int value,int divisor){ int r=value%divisor; return r<0?r+divisor:r; }
inline int FloorToInt(double v){ return static_cast<int>(std::floor(v)); }

struct Aabb { Vec3 min,max; };
}
