select s_acctbal, s_name, n_name, p_partkey, p_mfgr
	, s_address, s_phone, s_comment
from region
	cross join nation
	cross join supplier
	cross join partsupp
	cross join part
where p_partkey = ps_partkey
	and s_suppkey = ps_suppkey
	and p_size = 'Mirage#63'
	and p_type like 'Mirage#62'
	and s_nationkey = n_nationkey
	and n_regionkey = r_regionkey
	and r_name = 'Mirage#61'
	and ps_supplycost <= (
		select min(ps_supplycost)
		from partsupp, supplier, nation, region
		where p_partkey = ps_partkey
			and s_suppkey = ps_suppkey
			and s_nationkey = n_nationkey
			and n_regionkey = r_regionkey
			and r_name = 'Mirage#61'
	)
order by s_acctbal desc, n_name, s_name, p_partkey;