import { createContext, useContext, useEffect, useState } from 'react';
import { authApi } from '../api/authApi';

const TOKEN_KEY = 'threadhub_token';
const USER_KEY = 'threadhub_user';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [token, setToken] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const storedToken = localStorage.getItem(TOKEN_KEY);
    const storedUser = localStorage.getItem(USER_KEY);

    if (storedToken && storedUser) {
      try {
        setToken(storedToken);
        setUser(JSON.parse(storedUser));
      } catch (err) {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
      }
    }
    setLoading(false);

    const handleSessionExpired = () => {
      setToken(null);
      setUser(null);
    };

    window.addEventListener('threadhub:session-expired', handleSessionExpired);
    return () => {
      window.removeEventListener('threadhub:session-expired', handleSessionExpired);
    };
  }, []);

  const login = async (email, password) => {
    const authData = await authApi.login({ email, password });
    const { accessToken, user: loggedUser } = authData;

    localStorage.setItem(TOKEN_KEY, accessToken);
    localStorage.setItem(USER_KEY, JSON.stringify(loggedUser));

    setToken(accessToken);
    setUser(loggedUser);
    return authData;
  };

  const register = async (username, email, password) => {
    return await authApi.register({ username, email, password });
  };

  const logout = () => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    setToken(null);
    setUser(null);
  };

  const value = {
    user,
    token,
    loading,
    isAuthenticated: !!token && !!user,
    login,
    register,
    logout,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
