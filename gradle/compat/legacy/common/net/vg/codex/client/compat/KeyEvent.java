package net.vg.codex.client.compat;
public record KeyEvent(int key,int scan,int modifiers) { public boolean isEscape(){return key==256;} }
