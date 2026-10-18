"use client";

import React, { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Sparkles, LogIn, UserPlus } from "lucide-react";
import { getBaseUrl, loginApi, registerApi } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/** Matches UserService.MIN_PASSWORD_LENGTH / app.core.auth.MIN_PASSWORD_LENGTH. */
const MIN_PASSWORD_LENGTH = 6;
const MIN_USERNAME_LENGTH = 2;
/**
 * Both backends cap the password at 72 UTF-8 bytes, which is all bcrypt hashes.
 * maxLength counts characters, and a character is never fewer than one byte, so
 * this only blocks input that would certainly be rejected. A password of Chinese
 * characters can still exceed the byte limit and comes back as a 400.
 */
const MAX_PASSWORD_LENGTH = 72;

export default function LoginPage() {
  const [isLogin, setIsLogin] = useState(true);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [target, setTarget] = useState("");
  const router = useRouter();
  const { login, user, isLoading } = useAuth();

  // Nobody should sit on the login form while already signed in — the guard on
  // every other page would just let them straight through anyway.
  useEffect(() => {
    if (!isLoading && user) {
      router.replace("/chat");
    }
  }, [isLoading, user, router]);

  // Which backend the credentials go to. Read in an effect because it comes from
  // localStorage, which does not exist during server rendering.
  useEffect(() => {
    setTarget(getBaseUrl());
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);

    const result = isLogin
      ? await loginApi(username, password)
      : await registerApi(username, password);

    setLoading(false);

    if (result.success && result.data) {
      login(result.data.token, result.data.username, result.data.role);
      // replace, not push: the back button should not return to the login form.
      router.replace("/chat");
    } else {
      setError(result.error || (isLogin ? "登录失败" : "注册失败"));
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-background to-muted/50 p-4">
      <div className="w-full max-w-sm">
        {/* Logo */}
        <div className="text-center mb-8">
          <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-violet-500/20 to-indigo-600/20 flex items-center justify-center mx-auto mb-4">
            <Sparkles className="h-7 w-7 text-primary" />
          </div>
          <h1 className="text-2xl font-bold tracking-tight">RAG 知识库</h1>
          <p className="text-sm text-muted-foreground mt-1">智能知识问答系统</p>
        </div>

        {/* Card */}
        <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
          {/* Tabs */}
          <div className="flex gap-1 mb-6 p-1 rounded-lg bg-muted/50">
            <button
              onClick={() => { setIsLogin(true); setError(""); }}
              className={`flex-1 py-2 text-sm font-medium rounded-md transition-colors ${isLogin ? "bg-background shadow-sm text-foreground" : "text-muted-foreground"}`}
            >
              登录
            </button>
            <button
              onClick={() => { setIsLogin(false); setError(""); }}
              className={`flex-1 py-2 text-sm font-medium rounded-md transition-colors ${!isLogin ? "bg-background shadow-sm text-foreground" : "text-muted-foreground"}`}
            >
              注册
            </button>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">用户名</label>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="w-full px-3 py-2 text-sm rounded-lg border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary/50"
                placeholder="请输入用户名"
                required
                minLength={MIN_USERNAME_LENGTH}
                maxLength={50}
                autoComplete="username"
              />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">密码</label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full px-3 py-2 text-sm rounded-lg border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary/50"
                placeholder="请输入密码"
                required
                // Only enforced when registering: an existing account may predate
                // the rule, and blocking the login form would lock it out.
                minLength={isLogin ? 1 : MIN_PASSWORD_LENGTH}
                maxLength={MAX_PASSWORD_LENGTH}
                autoComplete={isLogin ? "current-password" : "new-password"}
              />
              {!isLogin && (
                <p className="text-[11px] text-muted-foreground mt-1.5">
                  至少 {MIN_PASSWORD_LENGTH} 个字符
                </p>
              )}
            </div>

            {error && (
              <div className="text-xs text-red-500 bg-red-500/10 rounded-md px-3 py-2">
                {error}
              </div>
            )}

            <Button type="submit" className="w-full" disabled={loading}>
              {loading ? (
                "处理中..."
              ) : isLogin ? (
                <><LogIn className="h-4 w-4 mr-2" /> 登录</>
              ) : (
                <><UserPlus className="h-4 w-4 mr-2" /> 注册</>
              )}
            </Button>
          </form>

          {/* The old build advertised "admin / admin123". That credential no
              longer exists: when ADMIN_PASSWORD is unset both backends generate a
              random one and print it once at startup. */}
          {isLogin && (
            <p className="text-xs text-muted-foreground text-center mt-4 leading-relaxed">
              管理员账号为 <code>ADMIN_USERNAME</code>（默认 <code>admin</code>），
              密码为 <code>ADMIN_PASSWORD</code>；未设置时后端启动日志里会打印一次随机密码。
            </p>
          )}
        </div>

        {target && (
          <p className="text-[11px] text-muted-foreground/70 text-center mt-4">
            当前后端：<code>{target}</code>
          </p>
        )}
      </div>
    </div>
  );
}
