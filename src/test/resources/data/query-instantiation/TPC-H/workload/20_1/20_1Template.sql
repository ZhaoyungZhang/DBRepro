select s_name, s_address
from partsupp
	cross join nation
		cross join supplier
	cross join part
where ps_suppkey = supplier.s_suppkey
	and ps_partkey = part.p_partkey
	and p_name like 'Mirage#66'
	and s_nationkey = n_nationkey
	and n_name = 'Mirage#65'
group by s_suppkey, s_name, s_address
order by s_name;