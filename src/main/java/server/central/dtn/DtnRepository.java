package server.central.dtn;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 서버 재시작 후에도 외부 callback과 시험을 연결하는 영속 저장소다. */
public interface DtnRepository extends JpaRepository<DtnJob, UUID> {
  List<DtnJob> findByStateIn(List<String> states);
  List<DtnJob> findTop50ByOrderByCreatedAtDesc();
}
