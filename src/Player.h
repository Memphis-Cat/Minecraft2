#pragma once
#include "World.h"

namespace mc {
struct InputState {
    bool forward{},back{},left{},right{},jump{},sneak{},sprint{},breakBlock{};
};

class Player {
public:
    Player();
    void Tick(const InputState& input,const World& world);
    void ApplyMouseDelta(double dx,double dy,double sensitivity=0.5);
    Vec3 Position() const{return position_;}
    Vec3 EyePosition() const{return {position_.x,position_.y+(sneaking_?1.54:1.62),position_.z};}
    Vec3 LookDirection() const;
    float YawDegrees() const{return yaw_;}
    float PitchDegrees() const{return pitch_;}
    bool Sneaking() const{return sneaking_;}
    bool Sprinting() const{return sprinting_;}
    double CameraBob() const{return cameraBob_;}
private:
    Aabb BoxAt(const Vec3& p) const;
    bool Collides(const Aabb& box,const World& world) const;
    bool SupportedAt(const Vec3& p,const World& world) const;
    double ClipAxis(const Aabb& box,double amount,int axis,const World& world) const;
    void Move(Vec3 delta,const World& world);
    Vec3 position_{0.5,11.0,0.5};
    Vec3 velocity_{};
    float yaw_{0.0f},pitch_{0.0f};
    bool onGround_{false},horizontalCollision_{false},sneaking_{false},sprinting_{false};
    bool wasForward_{false};
    int sprintTapTicks_{};
    double walked_{},cameraBob_{};
};
}
