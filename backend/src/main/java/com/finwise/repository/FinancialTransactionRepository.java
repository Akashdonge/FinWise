package com.finwise.repository;

import com.finwise.entity.FinancialTransaction;
import com.finwise.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
@Repository
public interface FinancialTransactionRepository extends JpaRepository<FinancialTransaction,Long> {
    Collection<Object> findByUserOrderByTransactionDateDesc(User user);

    Collection<Object> findByUser(User user);

}
