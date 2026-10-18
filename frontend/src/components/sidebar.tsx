"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  MessageSquare,
  Database,
  GitFork,
  Settings,
  Menu,
  X,
  Sun,
  Moon,
  ChevronLeft,
  Sparkles,
  LogOut,
  Shield,
  User,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/lib/auth-context";

const navItems = [
  { href: "/chat", label: "Chat", icon: MessageSquare, description: "AI-powered Q&A" },
  { href: "/knowledge", label: "Knowledge Base", icon: Database, description: "Manage documents" },
  { href: "/graph", label: "Knowledge Graph", icon: GitFork, description: "Explore entities" },
  { href: "/settings", label: "Settings", icon: Settings, description: "Configuration" },
];

export function Sidebar() {
  const pathname = usePathname();
  const router = useRouter();
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [darkMode, setDarkMode] = useState(true);
  const { user, logout, isAdmin } = useAuth();

  useEffect(() => {
    // Sync state with the class the inline script in layout.tsx already applied.
    const theme = localStorage.getItem("theme") || "dark";
    setDarkMode(theme === "dark");
  }, []);

  const handleLogout = () => {
    logout();
    // The guard would bounce us anyway, but going straight to /login avoids a
    // frame of the protected page rendering with no user.
    router.replace("/login");
  };

  const toggleTheme = () => {
    const newMode = !darkMode;
    setDarkMode(newMode);
    document.documentElement.classList.toggle("dark", newMode);
    localStorage.setItem("theme", newMode ? "dark" : "light");
  };

  return (
    <>
      {/* Mobile toggle button */}
      <Button
        variant="ghost"
        size="icon"
        className="fixed top-4 left-4 z-50 md:hidden"
        onClick={() => setMobileOpen(!mobileOpen)}
      >
        {mobileOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
      </Button>

      {/* Sidebar */}
      <aside
        className={cn(
          "flex flex-col h-full bg-card border-r border-border transition-all duration-300 ease-in-out",
          "fixed md:relative z-40",
          // Mobile: slide in/out
          mobileOpen
            ? "translate-x-0"
            : "-translate-x-full md:translate-x-0",
          // Desktop: collapsed state
          collapsed ? "md:w-[68px]" : "md:w-64",
          "w-64"
        )}
      >
        {/* Logo / Brand */}
        <div className="flex items-center gap-3 px-4 py-4 border-b border-border min-h-[64px]">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-violet-500 to-indigo-600 flex items-center justify-center flex-shrink-0 shadow-sm">
            <Sparkles className="h-4 w-4 text-white" />
          </div>
          {!collapsed && (
            <div className="overflow-hidden">
              <h1 className="text-sm font-semibold truncate">RAG Knowledge Base</h1>
              <p className="text-[11px] text-muted-foreground truncate">AI-powered search</p>
            </div>
          )}
        </div>

        {/* Navigation */}
        <nav className="flex-1 px-3 py-3 space-y-1">
          {navItems.map((item) => {
            const isActive = pathname === item.href || pathname.startsWith(item.href + "/");
            return (
              <Link
                key={item.href}
                href={item.href}
                className={cn(
                  "flex items-center gap-3 rounded-lg text-sm font-medium transition-all duration-150 relative group",
                  collapsed ? "px-2 py-2.5 justify-center" : "px-3 py-2.5",
                  isActive
                    ? "bg-primary/10 text-primary shadow-sm"
                    : "text-muted-foreground hover:bg-muted hover:text-foreground"
                )}
                onClick={() => setMobileOpen(false)}
                title={collapsed ? item.label : undefined}
              >
                <item.icon className={cn("flex-shrink-0", collapsed ? "h-5 w-5" : "h-4 w-4")} />
                {!collapsed && <span>{item.label}</span>}
                {isActive && (
                  <div className="absolute left-0 top-1/2 -translate-y-1/2 w-[3px] h-5 bg-primary rounded-r-full" />
                )}
                {/* Tooltip for collapsed state */}
                {collapsed && (
                  <div className="absolute left-full ml-2 px-2 py-1 bg-popover border border-border rounded-md text-xs font-medium whitespace-nowrap opacity-0 group-hover:opacity-100 pointer-events-none transition-opacity shadow-md z-50">
                    {item.label}
                  </div>
                )}
              </Link>
            );
          })}
        </nav>

        {/* Bottom section */}
        <div className="px-3 py-3 border-t border-border space-y-1">
          {/* User info */}
          {user && (
            <div
              className={cn(
                "flex items-center gap-3 px-3 py-2 rounded-lg bg-muted/30 mb-2",
                collapsed && "justify-center px-2"
              )}
            >
              <div className="w-7 h-7 rounded-full bg-primary/20 flex items-center justify-center flex-shrink-0">
                {isAdmin ? (
                  <Shield className="h-3.5 w-3.5 text-primary" />
                ) : (
                  <User className="h-3.5 w-3.5 text-primary" />
                )}
              </div>
              {!collapsed && (
                <div className="flex-1 min-w-0">
                  <p className="text-xs font-medium truncate">{user.username}</p>
                  <p className="text-[10px] text-muted-foreground">
                    {isAdmin ? "管理员" : "用户"}
                  </p>
                </div>
              )}
            </div>
          )}

          {/* Logout */}
          <Button
            variant="ghost"
            className={cn(
              "w-full gap-3 text-muted-foreground hover:text-destructive",
              collapsed ? "px-2 justify-center" : "justify-start"
            )}
            onClick={handleLogout}
            title={collapsed ? "退出登录" : undefined}
          >
            <LogOut className={cn("flex-shrink-0", collapsed ? "h-5 w-5" : "h-4 w-4")} />
            {!collapsed && <span className="text-sm">退出登录</span>}
          </Button>

          {/* Theme toggle */}
          <Button
            variant="ghost"
            className={cn(
              "w-full gap-3",
              collapsed ? "px-2 justify-center" : "justify-start"
            )}
            onClick={toggleTheme}
            title={collapsed ? (darkMode ? "Light Mode" : "Dark Mode") : undefined}
          >
            {darkMode ? (
              <Sun className={cn("flex-shrink-0", collapsed ? "h-5 w-5" : "h-4 w-4")} />
            ) : (
              <Moon className={cn("flex-shrink-0", collapsed ? "h-5 w-5" : "h-4 w-4")} />
            )}
            {!collapsed && (
              <span className="text-sm">
                {darkMode ? "Light Mode" : "Dark Mode"}
              </span>
            )}
          </Button>

          {/* Collapse toggle (desktop only) */}
          <Button
            variant="ghost"
            className={cn(
              "w-full gap-3 hidden md:flex",
              collapsed ? "px-2 justify-center" : "justify-start"
            )}
            onClick={() => setCollapsed(!collapsed)}
          >
            <ChevronLeft
              className={cn(
                "flex-shrink-0 transition-transform duration-300",
                collapsed ? "h-5 w-5 rotate-180" : "h-4 w-4"
              )}
            />
            {!collapsed && <span className="text-sm">Collapse</span>}
          </Button>
        </div>
      </aside>

      {/* Mobile overlay */}
      {mobileOpen && (
        <div
          className="fixed inset-0 bg-black/50 z-30 md:hidden backdrop-blur-sm"
          onClick={() => setMobileOpen(false)}
        />
      )}
    </>
  );
}
