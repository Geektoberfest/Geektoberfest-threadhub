const BASE_URL = import.meta.env.VITE_API_BASE_URL || '';
const TOKEN_KEY = 'threadhub_token';
const USER_KEY = 'threadhub_user';

export async function apiClient(endpoint, { body, headers: customHeaders, ...customConfig } = {}) {
  const token = localStorage.getItem(TOKEN_KEY);

  const headers = {
    'Content-Type': 'application/json',
    ...customHeaders,
  };

  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const config = {
    method: body ? 'POST' : 'GET',
    ...customConfig,
    headers,
  };

  if (body) {
    config.body = JSON.stringify(body);
  }

  const url = `${BASE_URL}${endpoint.startsWith('/') ? endpoint : `/${endpoint}`}`;

  let response;
  try {
    response = await fetch(url, config);
  } catch (error) {
    throw new Error('Network error. Unable to connect to backend server.');
  }

  let data = null;
  const contentType = response.headers.get('content-type');
  if (contentType && contentType.includes('application/json')) {
    try {
      data = await response.json();
    } catch {
      data = null;
    }
  }

  // Intercept 401 Unauthorized only for authenticated requests (exclude login attempts)
  const isAuthEndpoint = endpoint.includes('/api/auth/login') || endpoint.includes('/auth/login');
  if (response.status === 401 && !isAuthEndpoint && token) {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    window.dispatchEvent(new Event('threadhub:session-expired'));

    if (!window.location.pathname.includes('/login')) {
      window.location.href = '/login?expired=true';
    }
  }

  if (!response.ok) {
    const errorMessage = data?.message || data?.error || `Request failed with status ${response.status}`;
    const error = new Error(errorMessage);
    error.status = response.status;
    error.data = data;
    throw error;
  }

  return data;
}

export const api = {
  get: (endpoint, options) => apiClient(endpoint, { ...options, method: 'GET' }),
  post: (endpoint, body, options) => apiClient(endpoint, { ...options, method: 'POST', body }),
  put: (endpoint, body, options) => apiClient(endpoint, { ...options, method: 'PUT', body }),
  delete: (endpoint, options) => apiClient(endpoint, { ...options, method: 'DELETE' }),
};