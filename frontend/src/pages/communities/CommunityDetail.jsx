import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { communityApi } from '../../api/communityApi';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { Button } from '../../components/common/Button';
import { Loading } from '../../components/common/Loading';
import { ErrorMessage } from '../../components/common/ErrorMessage';

export function CommunityDetail() {
  const { id } = useParams();
  const { user, isAuthenticated } = useAuth();
  const toast = useToast();

  const [community, setCommunity] = useState(null);
  const [membership, setMembership] = useState({ isMember: false, role: null });
  const [members, setMembers] = useState([]);

  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [error, setError] = useState(null);
  const [actionError, setActionError] = useState(null);

  useEffect(() => {
    loadCommunityData();
  }, [id, isAuthenticated]);

  const loadCommunityData = async () => {
    setLoading(true);
    setError(null);
    try {
      const commData = await communityApi.getCommunityById(id);
      setCommunity(commData);

      const memberListData = await communityApi.getCommunityMembers(id);
      setMembers(memberListData || []);

      if (isAuthenticated) {
        try {
          const statusData = await communityApi.getMembershipStatus(id);
          setMembership(statusData);
        } catch {
          setMembership({ isMember: false, role: null });
        }
      }
    } catch (err) {
      setError(err.message || 'Failed to load community details.');
    } finally {
      setLoading(false);
    }
  };

  const handleJoin = async () => {
    setActionLoading(true);
    setActionError(null);
    try {
      await communityApi.joinCommunity(id);
      toast.success('You have joined the community.');
      await loadCommunityData();
    } catch (err) {
      const message = err.message || 'Failed to join community.';
      setActionError(message);
      toast.error(message);
    } finally {
      setActionLoading(false);
    }
  };

  const handleLeave = async () => {
    setActionLoading(true);
    setActionError(null);
    try {
      await communityApi.leaveCommunity(id);
      toast.success('You have left the community.');
      await loadCommunityData();
    } catch (err) {
      const message = err.message || 'Failed to leave community.';
      setActionError(message);
      toast.error(message);
    } finally {
      setActionLoading(false);
    }
  };

  if (loading) {
    return <Loading message="Loading community details..." />;
  }

  if (error || !community) {
    return (
      <div className="space-y-4">
        <Link to="/communities" className="text-xs text-orange-400 hover:underline">&larr; Back to communities</Link>
        <ErrorMessage title="Error Loading Community" message={error || 'Community not found.'} />
      </div>
    );
  }

  const isOwner = user && community.owner && user.id === community.owner.id;

  return (
    <div className="space-y-8">
      {/* Navigation Breadcrumb */}
      <Link to="/communities" className="text-xs font-semibold text-orange-400 hover:text-orange-300 transition-colors inline-flex items-center gap-1">
        &larr; Back to Communities
      </Link>

      {/* Community Header Banner */}
      <div className="p-6 sm:p-8 rounded-xl bg-slate-900 border border-slate-800 space-y-4 shadow-md">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div className="space-y-1">
            <h1 className="text-3xl font-extrabold text-white tracking-tight">t/{community.name}</h1>
            <p className="text-xs text-slate-400">
              Created by <strong className="text-slate-300">u/{community.owner?.username}</strong> on {new Date(community.createdAt).toLocaleDateString()}
            </p>
          </div>

          {/* Membership Actions */}
          {isAuthenticated ? (
            <div>
              {membership.isMember ? (
                isOwner ? (
                  <Button variant="secondary" size="sm" disabled title="Owners cannot leave their own community">
                    Owner (Cannot Leave)
                  </Button>
                ) : (
                  <Button variant="danger" size="sm" isLoading={actionLoading} onClick={handleLeave}>
                    Leave Community
                  </Button>
                )
              ) : (
                <Button variant="primary" size="sm" isLoading={actionLoading} onClick={handleJoin}>
                  Join Community
                </Button>
              )}
            </div>
          ) : (
            <Link to="/login">
              <Button variant="outline" size="sm">
                Log in to Join
              </Button>
            </Link>
          )}
        </div>

        <p className="text-sm text-slate-300 leading-relaxed border-t border-slate-800/80 pt-4">
          {community.description || 'No description provided for this community.'}
        </p>

        {actionError && <ErrorMessage message={actionError} />}
      </div>

      {/* Members Section */}
      <div className="space-y-4">
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <h2 className="text-lg font-bold text-white">
            Community Members ({members.length})
          </h2>
        </div>

        {members.length === 0 ? (
          <p className="text-xs text-slate-400">No members listed yet.</p>
        ) : (
          <div className="grid sm:grid-cols-2 md:grid-cols-3 gap-3">
            {members.map((m) => (
              <div key={m.userId} className="p-3.5 rounded-lg bg-slate-900/60 border border-slate-800 flex items-center justify-between">
                <div className="flex items-center gap-2.5">
                  <div className="w-7 h-7 rounded-full bg-slate-800 text-slate-300 font-bold text-xs flex items-center justify-center">
                    {m.username.charAt(0).toUpperCase()}
                  </div>
                  <div>
                    <span className="text-xs font-semibold text-slate-200 block">u/{m.username}</span>
                    <span className="text-[10px] text-slate-500 block">Joined {new Date(m.joinedAt).toLocaleDateString()}</span>
                  </div>
                </div>
                <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded ${
                  m.role === 'OWNER' ? 'bg-orange-500/20 text-orange-400 border border-orange-500/30' : 'bg-slate-800 text-slate-400'
                }`}>
                  {m.role}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
