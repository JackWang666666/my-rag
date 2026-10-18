"use client";

import React, { createContext, useContext, useEffect, useState } from "react";
import { USER_KEY } from "@/lib/api";
import type { User } from "@/types";

interface AuthContextType {
  user: User | null;
  /** True until the stored session has been read; guards against a login flash. */
  isLoading: boolean;
  login: (token: string, username: string, role: string) => void;
  logout: () => void;
  isAdmin: boolean;
}

const AuthContext = createContext<AuthContextType>({
  user: null,
  isLoading: true,
  login: () => {},
  logout: () => {},
  isAdmin: false,
});

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  // localStorage is not readable during SSR, so the first client render always
  // starts unauthenticated and settles in the effect below.
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const stored = localStorage.getItem(USER_KEY);
    if (stored) {
      try {
        const parsed = JSON.parse(stored);
        if (parsed?.token && parsed?.username) {
          setUser(parsed as User);
        } else {
          localStorage.removeItem(USER_KEY);
        }
      } catch {
        localStorage.removeItem(USER_KEY);
      }
    }
    setIsLoading(false);
  }, []);

  const login = (token: string, username: string, role: string) => {
    const userData: User = {
      token,
      username,
      role: role === "admin" ? "admin" : "user",
    };
    setUser(userData);
    localStorage.setItem(USER_KEY, JSON.stringify(userData));
  };

  const logout = () => {
    setUser(null);
    localStorage.removeItem(USER_KEY);
  };

  return (
    <AuthContext.Provider
      value={{ user, isLoading, login, logout, isAdmin: user?.role === "admin" }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
