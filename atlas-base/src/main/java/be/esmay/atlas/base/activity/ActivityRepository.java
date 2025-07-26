package be.esmay.atlas.base.activity;

import be.esmay.atlas.base.utils.Logger;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public final class ActivityRepository {

    private final SessionFactory sessionFactory;

    public void save(ServerActivity activity) {
        Transaction transaction = null;
        try (Session session = this.sessionFactory.openSession()) {
            transaction = session.beginTransaction();
            session.persist(activity);
            transaction.commit();
        } catch (Exception e) {
            if (transaction != null) {
                transaction.rollback();
            }
            Logger.error("Failed to save activity: {}", activity.getId(), e);
            throw new RuntimeException("Failed to save activity", e);
        }
    }

    public Optional<ServerActivity> findById(String id) {
        try (Session session = this.sessionFactory.openSession()) {
            ServerActivity activity = session.get(ServerActivity.class, id);
            return Optional.ofNullable(activity);
        } catch (Exception e) {
            Logger.error("Failed to find activity by id: {}", id, e);
            return Optional.empty();
        }
    }

    public List<ServerActivity> findRecent(int limit) {
        try (Session session = this.sessionFactory.openSession()) {
            Query<ServerActivity> query = session.createQuery(
                "FROM ServerActivity ORDER BY timestamp DESC", 
                ServerActivity.class
            );
            query.setMaxResults(limit);
            return query.getResultList();
        } catch (Exception e) {
            Logger.error("Failed to find recent activities", e);
            return Collections.emptyList();
        }
    }

    public List<ServerActivity> findByServerId(String serverId, int limit) {
        try (Session session = this.sessionFactory.openSession()) {
            Query<ServerActivity> query = session.createQuery(
                "FROM ServerActivity WHERE serverId = :serverId ORDER BY timestamp DESC", 
                ServerActivity.class
            );
            query.setParameter("serverId", serverId);
            query.setMaxResults(limit);
            return query.getResultList();
        } catch (Exception e) {
            Logger.error("Failed to find activities for server: {}", serverId, e);
            return Collections.emptyList();
        }
    }

    public List<ServerActivity> findByGroupName(String groupName, int limit) {
        try (Session session = this.sessionFactory.openSession()) {
            Query<ServerActivity> query = session.createQuery(
                "FROM ServerActivity WHERE groupName = :groupName ORDER BY timestamp DESC", 
                ServerActivity.class
            );
            query.setParameter("groupName", groupName);
            query.setMaxResults(limit);
            return query.getResultList();
        } catch (Exception e) {
            Logger.error("Failed to find activities for group: {}", groupName, e);
            return Collections.emptyList();
        }
    }

    public List<ServerActivity> findByActivityType(ActivityType activityType, int limit) {
        try (Session session = this.sessionFactory.openSession()) {
            Query<ServerActivity> query = session.createQuery(
                "FROM ServerActivity WHERE activityType = :activityType ORDER BY timestamp DESC", 
                ServerActivity.class
            );
            query.setParameter("activityType", activityType);
            query.setMaxResults(limit);
            return query.getResultList();
        } catch (Exception e) {
            Logger.error("Failed to find activities for type: {}", activityType, e);
            return Collections.emptyList();
        }
    }

    public List<ServerActivity> findByFilter(String serverId, String groupName, ActivityType activityType, int limit) {
        try (Session session = this.sessionFactory.openSession()) {
            StringBuilder hql = new StringBuilder("FROM ServerActivity WHERE 1=1");
            
            if (serverId != null && !serverId.trim().isEmpty()) {
                hql.append(" AND serverId = :serverId");
            }
            if (groupName != null && !groupName.trim().isEmpty()) {
                hql.append(" AND groupName = :groupName");
            }
            if (activityType != null) {
                hql.append(" AND activityType = :activityType");
            }
            
            hql.append(" ORDER BY timestamp DESC");
            
            Query<ServerActivity> query = session.createQuery(hql.toString(), ServerActivity.class);
            
            if (serverId != null && !serverId.trim().isEmpty()) {
                query.setParameter("serverId", serverId);
            }
            if (groupName != null && !groupName.trim().isEmpty()) {
                query.setParameter("groupName", groupName);
            }
            if (activityType != null) {
                query.setParameter("activityType", activityType);
            }
            
            query.setMaxResults(limit);
            return query.getResultList();
        } catch (Exception e) {
            Logger.error("Failed to find activities with filter", e);
            return Collections.emptyList();
        }
    }

    public long countAll() {
        try (Session session = this.sessionFactory.openSession()) {
            Query<Long> query = session.createQuery("SELECT COUNT(*) FROM ServerActivity", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            Logger.error("Failed to count activities", e);
            return 0L;
        }
    }

    public int deleteOlderThan(LocalDateTime cutoffDate) {
        Transaction transaction = null;
        try (Session session = this.sessionFactory.openSession()) {
            transaction = session.beginTransaction();
            
            Query<?> query = session.createQuery(
                "DELETE FROM ServerActivity WHERE timestamp < :cutoffDate"
            );
            query.setParameter("cutoffDate", cutoffDate);
            
            int deletedCount = query.executeUpdate();
            transaction.commit();
            
            Logger.debug("Deleted {} old activities older than {}", deletedCount, cutoffDate);
            return deletedCount;
        } catch (Exception e) {
            if (transaction != null) {
                transaction.rollback();
            }
            Logger.error("Failed to delete old activities", e);
            return 0;
        }
    }
}