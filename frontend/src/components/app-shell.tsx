"use client";

import React from "react";
import { usePathname } from "next/navigation";
import { AuthProvider } from "@/lib/auth-context";
import { Sidebar } from "@/components/sidebar";
import { AuthGuard } from "@/components/auth-guard";

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const isLoginPage = pathname === "/login";

  return (
    <AuthProvider>
      {isLoginPage ? (
        <div className="h-screen overflow-hidden bg-background">{children}</div>
      ) : (
        <AuthGuard>
          <div className="flex h-screen overflow-hidden bg-background">
            <Sidebar />
            <main className="flex-1 overflow-hidden">{children}</main>
          </div>
        </AuthGuard>
      )}
    </AuthProvider>
  );
}
