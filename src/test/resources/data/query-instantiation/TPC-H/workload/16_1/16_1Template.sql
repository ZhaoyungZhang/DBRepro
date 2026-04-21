select p_brand, p_type, p_size, count(DISTINCT ps_suppkey) as supplier_cnt
from partsupp, part
where p_partkey = ps_partkey
	and p_brand <> 'Mirage#20'
	and p_type not like 'Mirage#21'
	and p_size in (
		'Mirage#22', 
		'Mirage#23', 
		'Mirage#24', 
		'Mirage#25', 
		'Mirage#26', 
		'Mirage#27', 
		'Mirage#28', 
		'Mirage#29'
	)
	and ps_suppkey not in (
		select s_suppkey
		from supplier
		where s_comment like 'Mirage#19'
	)
group by p_brand, p_type, p_size
order by supplier_cnt desc, p_brand, p_type, p_size;