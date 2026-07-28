package com.memphiscat.legacyculling.font;

public final class FontRenderKey {
    public final String text;
    public final int xBits;
    public final int yBits;
    public final int color;
    public final boolean shadow;

    public FontRenderKey(String text, int xBits, int yBits, int color, boolean shadow) {
        this.text = text;
        this.xBits = xBits;
        this.yBits = yBits;
        this.color = color;
        this.shadow = shadow;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof FontRenderKey)) return false;
        FontRenderKey other = (FontRenderKey) object;
        return xBits == other.xBits && yBits == other.yBits && color == other.color
                && shadow == other.shadow && text.equals(other.text);
    }

    @Override
    public int hashCode() {
        int result = text.hashCode();
        result = 31 * result + xBits;
        result = 31 * result + yBits;
        result = 31 * result + color;
        result = 31 * result + (shadow ? 1 : 0);
        return result;
    }
}
