package seen.kai.checker.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import seen.kai.checker.entity.StationEntity;

@ApplicationScoped
public class StationRepository implements PanacheRepositoryBase<StationEntity, String> {
}
