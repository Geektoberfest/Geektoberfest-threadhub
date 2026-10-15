import { api } from './client';

export const communityApi = {
  createCommunity: (data) => api.post('/api/communities', data),
  getAllCommunities: (params = {}) => {
    const query = new URLSearchParams(params).toString();
    return api.get(`/api/communities${query ? `?${query}` : ''}`);
  },
  getCommunityById: (id) => api.get(`/api/communities/${id}`),
  getCommunityByName: (name) => api.get(`/api/communities/name/${name}`),
  joinCommunity: (communityId) => api.post(`/api/communities/${communityId}/join`),
  leaveCommunity: (communityId) => api.delete(`/api/communities/${communityId}/leave`),
  getMembershipStatus: (communityId) => api.get(`/api/communities/${communityId}/membership`),
  getCommunityMembers: (communityId) => api.get(`/api/communities/${communityId}/members`),
};