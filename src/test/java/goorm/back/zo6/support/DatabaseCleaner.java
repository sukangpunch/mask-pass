package goorm.back.zo6.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 각 테스트 실행 전 db 상태롤 초기화 하는 컴포넌트
// INFORMATION_SCHEMA에서 현재 DB의 모든 테이블을 조회한 뒤 TRUNCATE를 실행
// FOREIGN_KEY_CHECKS = 0으로 외래키 제약을 임시 해제하여 테이블 간 의존성 없이 순서와 무관하게 truncate
@Component
public class DatabaseCleaner {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void clear() {
        em.clear();
        truncate();
    }

    private void truncate() {
        List<String> tableNames = getTableNames();
        if (tableNames.isEmpty()) return;
        String tables = String.join(", ", tableNames);
        // RESTART IDENTITY: 시퀀스(auto increment) 초기화
        // CASCADE: 외래키 참조 관계에 상관없이 truncate
        em.createNativeQuery("TRUNCATE TABLE " + tables + " RESTART IDENTITY CASCADE").executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private List<String> getTableNames() {
        String sql = """
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                     AND table_type = 'BASE TABLE'
                     """;

        return em.createNativeQuery(sql).getResultList();
    }
}

