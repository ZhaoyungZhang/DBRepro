-- cannotFindArgs:{id:79,data:'$0',operand:public.customer.c_acctbal},{id:70,data:'0.0',operand:public.customer.c_acctbal}
select cntrycode, count(*) as numcust, sum(c_acctbal) as totacctbal
from (
	select substring(c_phone from 1 for 2) as cntrycode, c_acctbal
	from customer
	where substring(c_phone from 1 for 2) in (
			'Mirage#80', 
			'Mirage#81', 
			'Mirage#82', 
			'Mirage#83', 
			'Mirage#84', 
			'Mirage#85', 
			'Mirage#86'
		)
		and c_acctbal > (
			select avg(c_acctbal)
			from customer
			where c_acctbal > 0.00
				and substring(c_phone from 1 for 2) in (
					'Mirage#71', 
					'Mirage#72', 
					'Mirage#73', 
					'Mirage#74', 
					'Mirage#75', 
					'Mirage#76', 
					'Mirage#77'
				)
		)
		and not exists (
			select *
			from orders
			where o_custkey = c_custkey
		)
) custsale
group by cntrycode
order by cntrycode;