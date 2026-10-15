import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { communityApi } from '../../api/communityApi';
import { useAuth } from '../../context/AuthContext';
import { Button } from '../../components/common/Button';
import { Loading } from '../../components/common/Loading';
import { ErrorMessage } from '../../components/common/ErrorMessage';

export function Communities() {
  const [communities, setCommunities] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [sortBy, setSortBy] = useState('createdAt,desc');
  const { isAuthenticated } = useAuth();

  useEffect(() => {
    fetchCommunities(page, sortBy);
  }, [page, sortBy]);

  const fetchCommunities = async (targetPage = 0, sort = 'createdAt,desc') => {
    setLoading(true);
    setError(null);
    try {
      const [sortField, sortDir] = sort.split(',');
      const params = {
        page: targetPage,
        size: 10,
        sort: `${sortField},${sortDir}`,
      };

      // Calls communityApi with query parameters if supported, or default getAllCommunities
      const data = communityApi.getAllCommunities.length > 0
        ? await communityApi.getAllCommunities(params)
        : await communityApi.getAllCommunities();

      // Support both Spring Page payloads (data.content) and raw arrays (fallback)
      if (data && Array.isArray(data.content)) {
        setCommunities(data.content);
        setTotalPages(data.totalPages || 0);
      } else if (Array.isArray(data)) {
        setCommunities(data);
        setTotalPages(1);
      } else {
        setCommunities([]);
        setTotalPages(0);
      }
    } catch (err) {
      setError(err.message || 'Unable to load communities.');
    } finally {
      setLoading(false);
    }
  };

  const handleSortChange = (e) => {
    setSortBy(e.target.value);
    setPage(0);
  };

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-slate-800">
        <div>
          <h1 className="text-2xl font-bold text-white tracking-tight">Communities</h1>
          <p className="text-xs text-slate-400 mt-1">Explore topic-based discussion groups</p>
        </div>

        <div className="flex items-center gap-3">
          {/* Sorting Dropdown */}
          <select
            value={sortBy}
            onChange={handleSortChange}
            className="bg-slate-900 border border-slate-800 text-xs text-slate-300 rounded-lg px-3 py-1.5 focus:outline-none focus:border-orange-500"
          >
            <option value="createdAt,desc">Newest First</option>
            <option value="createdAt,asc">Oldest First</option>
            <option value="name,asc">Name (A-Z)</option>
            <option value="name,desc">Name (Z-A)</option>
          </select>

          {isAuthenticated && (
            <Link to="/communities/create">
              <Button variant="primary" size="sm">
                + Create Community
              </Button>
            </Link>
          )}
        </div>
      </div>

      {/* State Handling */}
      {loading && <Loading message="Loading communities..." />}

      {error && <ErrorMessage title="Failed to load communities" message={error} />}

      {!loading && !error && communities.length === 0 && (
        <div className="text-center py-12 bg-slate-900/50 border border-slate-800/80 rounded-xl space-y-3">
          <p className="text-sm font-medium text-slate-400">No communities found.</p>
          {isAuthenticated && (
            <Link to="/communities/create">
              <Button variant="outline" size="sm">
                Be the first to create one
              </Button>
            </Link>
          )}
        </div>
      )}

      {/* Communities Grid */}
      {!loading && !error && communities.length > 0 && (
        <>
          <div className="grid md:grid-cols-2 gap-4">
            {communities.map((comm) => (
              <Link
                key={comm.id}
                to={`/communities/${comm.id}`}
                className="group block p-5 rounded-xl bg-slate-900 border border-slate-800 hover:border-orange-500/50 transition-all duration-200 shadow-sm"
              >
                <div className="flex items-start justify-between gap-2 mb-2">
                  <h2 className="text-lg font-bold text-white group-hover:text-orange-400 transition-colors">
                    t/{comm.name}
                  </h2>
                  <span className="text-xs font-medium text-slate-400 bg-slate-800 px-2 py-0.5 rounded-full shrink-0">
                    {comm.memberCount || 1} {comm.memberCount === 1 ? 'member' : 'members'}
                  </span>
                </div>
                <p className="text-xs text-slate-400 line-clamp-2 mb-4 leading-relaxed">
                  {comm.description || 'No description provided.'}
                </p>
                <div className="text-[11px] text-slate-500 flex items-center justify-between border-t border-slate-800/60 pt-3">
                  <span>
                    Owner: <strong className="text-slate-400">u/{comm.owner?.username || 'unknown'}</strong>
                  </span>
                  <span>Created {new Date(comm.createdAt).toLocaleDateString()}</span>
                </div>
              </Link>
            ))}
          </div>

          {/* Pagination Controls */}
          {totalPages > 1 && (
            <div className="flex items-center justify-between border-t border-slate-800/80 pt-4">
              <span className="text-xs text-slate-400">
                Page {page + 1} of {totalPages}
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setPage((prev) => Math.max(prev - 1, 0))}
                  disabled={page === 0}
                >
                  Previous
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setPage((prev) => prev + 1)}
                  disabled={page >= totalPages - 1}
                >
                  Next
                </Button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}