package net.vg.codex.client.compat;
public record MouseButtonEvent(double x,double y,int button) { public boolean hasShiftDown(){return net.minecraft.client.gui.screens.Screen.hasShiftDown();} }
